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

#pragma once

#include <boost/cstdint.hpp>
#include <string>
#include <vector>

#include "common/statusor.h"

namespace starrocks {
void base64_encode(const std::string& in, std::string* out);

// Utility method to decode base64 encoded strings.  Also not extremely
// performant.
// Returns true unless the string could not be correctly decoded.
bool base64_decode(const std::string& in, std::string* out);

// refers to https://stackoverflow.com/questions/154536/encode-decode-urls-in-c
std::string url_encode(const std::string& decoded);

// Utility method to decode a string that was URL-encoded. Returns
// true unless the string could not be correctly decoded.
//Example:
//    std::string decoded;
//    StatusOr<std::string> ret = url_decode("Load%E6%A1%8C", &decoded); //decoded == "Load桌"
StatusOr<std::string> url_decode(const std::string& in);

} // namespace starrocks
