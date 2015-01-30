/*
 * Copyright 2015 jonathan.colt.
 *
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
 */
package com.jivesoftware.os.miru.logappender;

import java.util.Arrays;
import org.testng.annotations.Test;

/**
 *
 * @author jonathan.colt
 */
public class HttpPosterNGTest {

    public HttpPosterNGTest() {
    }

    @Test
    public void testSend() throws Exception {

        HttpPoster httpPoster = new HttpPoster("soa-prime-data7.phx1.jivehosted.com", 10000, 30000);

        httpPoster.send(Arrays.asList(new MiruLogEvent(null, "dev", "foo", "bar", "baz", "1", "INFO", "main", "logger", "hi", "time", null)));
        httpPoster.send(Arrays.asList(new MiruLogEvent(null, "dev", "foo", "bar", "baz", "1", "INFO", "main", "logger", "hi", "time", null)));
    }

}
