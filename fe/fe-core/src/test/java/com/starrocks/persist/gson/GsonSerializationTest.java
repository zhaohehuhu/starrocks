// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.persist.gson;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.google.gson.annotations.SerializedName;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.MaterializedIndexMeta;
import com.starrocks.catalog.Type;
import com.starrocks.common.io.FastByteArrayOutputStream;
import com.starrocks.common.io.Text;
import com.starrocks.common.io.Writable;
import com.starrocks.persist.gson.GsonSerializationTest.Key.MyEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.starrocks.catalog.KeysType.PRIMARY_KEYS;
import static com.starrocks.thrift.TStorageType.COLUMN;

/*
 * This unit test provides examples about how to make a class serializable.
 *
 *    "OrigClassA" is a class includes user-defined class "InnerClassA".
 *    And "InnerClassA" includes some collections which contain another user-defined class "InnerClassB".
 *
 *    And there are 2 other classes "OriginClassADifferentMembers" and "OriginClassADifferentMemberName".
 *    "OriginClassADifferentMembers" shows how to add/remove members of a serializable class.
 *    "OriginClassADifferentMemberName" shows how to modify members' name of a serializable class.
 *
 *    Every fields which need to be serialized should be with annotation @SerializedName.
 *    @SerializedName has 2 attributes:
 *      1. value(required): the name of this field in Json string.
 *      2. alternate(optional): if we want to use new name for a field and its value in annotation, use alternate.
 *
 */
public class GsonSerializationTest {
    private static String fileName = "./GsonSerializationTest";

    public static class OrigClassA implements Writable {
        @SerializedName(value = "classA1")
        public InnerClassA classA1;
        public InnerClassA ignoreClassA2;
        @SerializedName(value = "flag")
        public int flag = 0;

        public OrigClassA(int flag) {
            this.flag = flag;
            classA1 = new InnerClassA(1);
            ignoreClassA2 = new InnerClassA(2);
        }

        @Override
        public void write(DataOutput out) throws IOException {
            String json = GsonUtils.GSON.toJson(this);
            System.out.println(json);
            Text.writeString(out, json);
        }

        public static OrigClassA read(DataInput in) throws IOException {
            String json = Text.readString(in);
            System.out.println(json);
            return GsonUtils.GSON.fromJson(json, OrigClassA.class);
        }
    }

    public static class InnerClassA implements Writable {
        @SerializedName(value = "list1")
        public List<String> list1 = Lists.newArrayList();
        @SerializedName(value = "map1")
        public Map<Long, String> map1 = Maps.newHashMap();
        @SerializedName(value = "map2")
        public Map<Integer, InnerClassB> map2 = Maps.newHashMap();
        @SerializedName(value = "set1")
        public Set<String> set1 = Sets.newHashSet();
        @SerializedName(value = "flag")
        public int flag = 0;

        public InnerClassA(int flag) {
            list1.add("string1");
            list1.add("string2");

            map1.put(1L, "value1");
            map1.put(2L, "value2");

            map2.put(1, new InnerClassB(1));
            map2.put(2, new InnerClassB(2));

            set1.add("set1");
            set1.add("set2");

            this.flag = flag;
        }

        @Override
        public void write(DataOutput out) throws IOException {
            Text.writeString(out, GsonUtils.GSON.toJson(this));
        }

        public static InnerClassA read(DataInput in) throws IOException {
            return GsonUtils.GSON.fromJson(Text.readString(in), InnerClassA.class);
        }
    }

    public static class InnerClassB implements Writable {
        @SerializedName(value = "flag")
        public int flag = 0;
        @SerializedName(value = "hashMultimap")
        public Multimap<Long, String> hashMultimap = HashMultimap.create();
        @SerializedName(value = "hashBasedTable")
        public Table<Long, String, Long> hashBasedTable = HashBasedTable.create();
        @SerializedName(value = "arrayListMultimap")
        public Multimap<Long, String> arrayListMultimap = ArrayListMultimap.create();

        public int ignoreField = 0;

        public InnerClassB(int flag) {
            this.flag = flag;

            this.hashMultimap.put(1L, "string1");
            this.hashMultimap.put(1L, "string2");
            this.hashMultimap.put(2L, "string3");

            this.hashBasedTable.put(1L, "col1", 1L);
            this.hashBasedTable.put(2L, "col2", 2L);

            this.arrayListMultimap.put(1L, "value1");
            this.arrayListMultimap.put(1L, "value2");

            this.ignoreField = flag;
        }

        @Override
        public void write(DataOutput out) throws IOException {
            Text.writeString(out, GsonUtils.GSON.toJson(this));
        }

        public static InnerClassB read(DataInput in) throws IOException {
            return GsonUtils.GSON.fromJson(Text.readString(in), InnerClassB.class);
        }
    }

    // same as OriginClassA, but:
    // 1. without member classA1;
    // 2. add a new member classA3;
    public static class OriginClassADifferentMembers implements Writable {
        @SerializedName(value = "classA3")
        public InnerClassA classA3;
        public InnerClassA ignoreClassA2;
        @SerializedName(value = "flag")
        public int flag = 0;

        public OriginClassADifferentMembers(int flag) {
            this.flag = flag;
            classA3 = new InnerClassA(3);
            ignoreClassA2 = new InnerClassA(2);
        }

        @Override
        public void write(DataOutput out) throws IOException {
            String json = GsonUtils.GSON.toJson(this);
            System.out.println(json);
            Text.writeString(out, json);
        }

        public static OriginClassADifferentMembers read(DataInput in) throws IOException {
            String json = Text.readString(in);
            System.out.println(json);
            return GsonUtils.GSON.fromJson(json, OriginClassADifferentMembers.class);
        }
    }

    // same as OriginClassA, but:
    // 1. change classA1's name to classA1ChangeName
    // 2. change ignoreClassA2's name to ignoreClassA2ChangeName
    // 3. change flag's name to flagChangeName, and also change its serialized name to flagChangeName
    public static class OriginClassADifferentMemberName implements Writable {
        @SerializedName(value = "classA1")
        public InnerClassA classA1ChangeName;
        public InnerClassA ignoreClassA2ChangeName;
        @SerializedName(value = "flagChangeName", alternate = {"flag"})
        public int flagChangeName = 0;

        public OriginClassADifferentMemberName(int flag) {
            this.flagChangeName = flag;
            classA1ChangeName = new InnerClassA(1);
            ignoreClassA2ChangeName = new InnerClassA(2);
        }

        @Override
        public void write(DataOutput out) throws IOException {
            String json = GsonUtils.GSON.toJson(this);
            System.out.println(json);
            Text.writeString(out, json);
        }

        public static OriginClassADifferentMemberName read(DataInput in) throws IOException {
            String json = Text.readString(in);
            System.out.println(json);
            return GsonUtils.GSON.fromJson(json, OriginClassADifferentMemberName.class);
        }
    }

    @AfterEach
    public void tearDown() {
        File file = new File(fileName);
        file.delete();
    }

    /*
     * Test write read with same classes.
     */
    @Test
    public void testNormal() throws IOException {
        // 1. Write objects to file
        File file = new File(fileName);
        file.createNewFile();
        DataOutputStream out = new DataOutputStream(new FileOutputStream(file));

        OrigClassA classA = new OrigClassA(1);
        classA.write(out);
        out.flush();
        out.close();

        // 2. Read objects from file
        DataInputStream in = new DataInputStream(new FileInputStream(file));

        OrigClassA readClassA = OrigClassA.read(in);
        Assertions.assertEquals(1, readClassA.flag);
        Assertions.assertEquals(1, readClassA.classA1.flag);
        Assertions.assertNull(readClassA.ignoreClassA2);

        Assertions.assertEquals(Lists.newArrayList("string1", "string2"), readClassA.classA1.list1);
        Assertions.assertTrue(readClassA.classA1.map1.containsKey(1L));
        Assertions.assertTrue(readClassA.classA1.map1.containsKey(2L));
        Assertions.assertEquals("value1", readClassA.classA1.map1.get(1L));
        Assertions.assertEquals("value2", readClassA.classA1.map1.get(2L));

        Assertions.assertTrue(readClassA.classA1.map2.containsKey(1));
        Assertions.assertTrue(readClassA.classA1.map2.containsKey(2));
        Assertions.assertEquals(1, readClassA.classA1.map2.get(1).flag);
        Assertions.assertEquals(2, readClassA.classA1.map2.get(2).flag);
        Assertions.assertEquals(0, readClassA.classA1.map2.get(1).ignoreField);
        Assertions.assertEquals(0, readClassA.classA1.map2.get(2).ignoreField);
        Assertions.assertEquals(Sets.newHashSet("set1", "set2"), readClassA.classA1.set1);

        Table<Long, String, Long> hashBasedTable = readClassA.classA1.map2.get(1).hashBasedTable;
        Assertions.assertEquals("HashBasedTable", hashBasedTable.getClass().getSimpleName());
        Multimap<Long, String> hashMultimap = readClassA.classA1.map2.get(1).hashMultimap;
        Assertions.assertEquals("HashMultimap", hashMultimap.getClass().getSimpleName());
        Multimap<Long, String> arrayListMultimap = readClassA.classA1.map2.get(1).arrayListMultimap;
        Assertions.assertEquals("ArrayListMultimap", arrayListMultimap.getClass().getSimpleName());
        Assertions.assertEquals(Lists.newArrayList("value1", "value2"), arrayListMultimap.get(1L));

        in.close();
    }

    /*
     * Test write origin class, and read in new class with different members
     */
    @Test
    public void testWithDifferentMembers() throws IOException {
        // 1. Write objects to file
        File file = new File(fileName);
        file.createNewFile();
        DataOutputStream out = new DataOutputStream(new FileOutputStream(file));

        OrigClassA classA = new OrigClassA(1);
        classA.write(out);
        out.flush();
        out.close();

        // 2. Read objects from file
        DataInputStream in = new DataInputStream(new FileInputStream(file));

        OriginClassADifferentMembers readClassA = OriginClassADifferentMembers.read(in);
        Assertions.assertEquals(1, readClassA.flag);
        Assertions.assertNull(readClassA.classA3);
        Assertions.assertNull(readClassA.ignoreClassA2);
        in.close();
    }

    /*
     * Test write origin class, and read in new class with different member names
     */
    @Test
    public void testWithDifferentMemberNames() throws IOException {
        // 1. Write objects to file
        File file = new File(fileName);
        file.createNewFile();
        DataOutputStream out = new DataOutputStream(new FileOutputStream(file));

        OrigClassA classA = new OrigClassA(1);
        classA.write(out);
        out.flush();
        out.close();

        // 2. Read objects from file
        DataInputStream in = new DataInputStream(new FileInputStream(file));

        OriginClassADifferentMemberName readClassA = OriginClassADifferentMemberName.read(in);
        Assertions.assertEquals(1, readClassA.flagChangeName);
        Assertions.assertEquals(1, readClassA.classA1ChangeName.flag);
        Assertions.assertNull(readClassA.ignoreClassA2ChangeName);

        Assertions.assertEquals(Lists.newArrayList("string1", "string2"), readClassA.classA1ChangeName.list1);
        Assertions.assertTrue(readClassA.classA1ChangeName.map1.containsKey(1L));
        Assertions.assertTrue(readClassA.classA1ChangeName.map1.containsKey(2L));
        Assertions.assertEquals("value1", readClassA.classA1ChangeName.map1.get(1L));
        Assertions.assertEquals("value2", readClassA.classA1ChangeName.map1.get(2L));

        Assertions.assertTrue(readClassA.classA1ChangeName.map2.containsKey(1));
        Assertions.assertTrue(readClassA.classA1ChangeName.map2.containsKey(2));
        Assertions.assertEquals(1, readClassA.classA1ChangeName.map2.get(1).flag);
        Assertions.assertEquals(2, readClassA.classA1ChangeName.map2.get(2).flag);
        Assertions.assertEquals(0, readClassA.classA1ChangeName.map2.get(1).ignoreField);
        Assertions.assertEquals(0, readClassA.classA1ChangeName.map2.get(2).ignoreField);
        Assertions.assertEquals(Sets.newHashSet("set1", "set2"), readClassA.classA1ChangeName.set1);
        in.close();
    }

    public static class MultiMapClassA implements Writable {
        @SerializedName(value = "map")
        public Multimap<Key, Long> map = HashMultimap.create();

        public MultiMapClassA() {
            map.put(new Key(MyEnum.TYPE_A, "key1"), 1L);
            map.put(new Key(MyEnum.TYPE_B, "key2"), 2L);
        }

        @Override
        public void write(DataOutput out) throws IOException {
            String json = GsonUtils.GSON.toJson(this);
            Text.writeString(out, json);
        }

        public static MultiMapClassA read(DataInput in) throws IOException {
            String json = Text.readString(in);
            MultiMapClassA classA = GsonUtils.GSON.fromJson(json, MultiMapClassA.class);
            return classA;
        }
    }

    public static class Key {
        public enum MyEnum {
            TYPE_A, TYPE_B
        }

        @SerializedName(value = "type")
        public MyEnum type;
        @SerializedName(value = "value")
        public String value;

        public Key(MyEnum type, String value) {
            this.type = type;
            this.value = value;
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, value);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Key)) {
                return false;
            }

            if (this == obj) {
                return true;
            }

            Key other = (Key) obj;
            return other.type == this.type && other.value.equals(this.value);
        }

        @Override
        public String toString() {
            return type + ":" + value;
        }
    }

    @Test
    public void testMultiMapWithCustomKey() throws IOException {
        // 1. Write objects to file
        File file = new File(fileName);
        file.createNewFile();
        DataOutputStream out = new DataOutputStream(new FileOutputStream(file));

        MultiMapClassA classA = new MultiMapClassA();
        classA.write(out);
        out.flush();
        out.close();

        // 2. Read objects from file
        DataInputStream in = new DataInputStream(new FileInputStream(file));

        MultiMapClassA readClassA = MultiMapClassA.read(in);
        Assertions.assertEquals(Sets.newHashSet(new Key(MyEnum.TYPE_A, "key1"), new Key(MyEnum.TYPE_B, "key2")),
                readClassA.map.keySet());
    }

    private static class GsonPrePostProcessTest implements Writable, GsonPreProcessable, GsonPostProcessable {
        private int a;

        @SerializedName(value = "b")
        private String b = "";

        public GsonPrePostProcessTest(int a) {
            this.a = a;
        }

        @Override
        public void gsonPreProcess() throws IOException {
            b = String.valueOf(a);
        }
        
        @Override
        public void gsonPostProcess() throws IOException {
            a = Integer.parseInt(b);
        }

        @Override
        public void write(DataOutput out) throws IOException {
            String json = GsonUtils.GSON.toJson(this);
            System.out.println("write: " + json);
            Text.writeString(out, json);
        }

        public static GsonPrePostProcessTest read(DataInput in) throws IOException {
            String json = Text.readString(in);
            System.out.println("read: " + json);
            return GsonUtils.GSON.fromJson(json, GsonPrePostProcessTest.class);
        }
    }

    @Test
    public void testGsonPrePostProcess() throws IOException {
        GsonPrePostProcessTest prePost = new GsonPrePostProcessTest(2);
        Assertions.assertEquals(2, prePost.a);
        Assertions.assertTrue(prePost.b.equals(""));

        FastByteArrayOutputStream byteArrayOutputStream = new FastByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(byteArrayOutputStream)) {
            prePost.write(out);
            out.flush();
        }

        GsonPrePostProcessTest newPrePost = null;
        try (DataInputStream in = new DataInputStream(byteArrayOutputStream.getInputStream())) {
            newPrePost = GsonPrePostProcessTest.read(in);
        }
        byteArrayOutputStream.close();

        Assertions.assertEquals(2, newPrePost.a);
        Assertions.assertTrue(newPrePost.b.equals("2"));
    }

    @Test
    public void testMaterializedIndexMetaGsonProcess() throws Exception {
        MaterializedIndexMeta indexMeta = new MaterializedIndexMeta(1, Lists.newArrayList(new Column("k1", Type.INT)), 1, 1,
                (short) 1, COLUMN, PRIMARY_KEYS, null);
        FastByteArrayOutputStream byteArrayOutputStream = new FastByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(byteArrayOutputStream)) {
            indexMeta.write(out);
            out.flush();
        }

        MaterializedIndexMeta copied = null;
        try (DataInputStream in = new DataInputStream(byteArrayOutputStream.getInputStream())) {
            copied = MaterializedIndexMeta.read(in);
        }
        byteArrayOutputStream.close();
        Assertions.assertTrue(copied.sortKeyIdxes == null);
        Assertions.assertTrue(copied.sortKeyUniqueIds == null);
    }
}
