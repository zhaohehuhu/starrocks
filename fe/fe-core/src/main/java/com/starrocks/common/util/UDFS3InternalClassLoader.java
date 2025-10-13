// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.common.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import static com.starrocks.connector.share.credential.CloudConfigurationConstants.AWS_S3_ACCESS_KEY;
import static com.starrocks.connector.share.credential.CloudConfigurationConstants.AWS_S3_REGION;
import static com.starrocks.connector.share.credential.CloudConfigurationConstants.AWS_S3_SECRET_KEY;

public class UDFS3InternalClassLoader extends URLClassLoader {

    private static final Logger LOG = LogManager.getLogger(UDFS3InternalClassLoader.class);

    private S3Client s3Client;

    private String bucketName;

    private String key;

    public UDFS3InternalClassLoader(String udfPath, Map<String, String> svProperties) throws IOException {
        super(new java.net.URL[]{});
        initialize(udfPath, svProperties);
    }

    private void initialize(String udfPath, Map<String, String> svProperties) {
        AwsBasicCredentials awsBasicCredential = AwsBasicCredentials.create(
                svProperties.get(AWS_S3_ACCESS_KEY),
                svProperties.get(AWS_S3_SECRET_KEY));
        this.s3Client = S3Client.builder()
                .region(Region.of(svProperties.get(AWS_S3_REGION)))
                .credentialsProvider(StaticCredentialsProvider.create(awsBasicCredential))
                .build();
        URI uri = URI.create(udfPath);
        this.bucketName = uri.getHost();
        this.key = uri.getPath().startsWith("/") ? uri.getPath().substring(1) : uri.getPath();
    }

    @Override
    public URL getResource(String name) {
        Objects.requireNonNull(name);
        try {
            return new URL("s3://" + bucketName + "/" + name);
        } catch (MalformedURLException e) {
            LOG.error("failed to get resource from {}", name, e);
            return null;
        }
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        try {
            return s3Client.getObject(GetObjectRequest.builder().bucket(bucketName).key(key).build());
        } catch (Exception e) {
            LOG.error("failed to open resource stream for {}", name, e);
            return null;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] classBytes = loadClassBytesFromJar(name);
        if (classBytes == null) {
            throw new ClassNotFoundException(name);
        }
        return defineClass(name, classBytes, 0, classBytes.length);
    }

    private byte[] loadClassBytesFromJar(String className) {
        String classPath = className.replace('.', '/') + ".class";
        try (InputStream in = s3Client.getObject(GetObjectRequest.builder().bucket(bucketName).key(key).build());
                JarInputStream jis = new JarInputStream(in)) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().equals(classPath)) {
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    jis.transferTo(byteArrayOutputStream);
                    return byteArrayOutputStream.toByteArray();
                }
            }
        } catch (IOException e) {
            LOG.error("Failed to load class {} from S3 JAR", className, e);
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        super.close();
        s3Client.close();
    }
}
