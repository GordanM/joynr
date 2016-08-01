/*jslint node: true */

/*
 * #%L
 * %%
 * Copyright (C) 2016 BMW Car IT GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

//var testbase = require("test-base");
//jasmine.getEnv().addReporter(new testbase.TestReporter());
var consumerBase = require("./consumer.base.js");

//disable log
console.log = function() {};

describe("js consumer performance test", function() {

    beforeEach(function(done) {
        consumerBase.initialize().then(done);
    });

    it("EchoString", function(done) {
        consumerBase.echoString().then(done).catch(done.fail);
    });

    it("EchoComplexStruct", function(done) {
        consumerBase.echoComplexStruct().then(done).catch(done.fail);
    });

    it("EchoByteArray", function(done) {
        consumerBase.echoByteArray().then(done).catch(done.fail);
    });
});
