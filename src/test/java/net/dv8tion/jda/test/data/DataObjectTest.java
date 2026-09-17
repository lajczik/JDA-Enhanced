/*
 * Copyright 2015 Austin Keener, Michael Ritter, Florian Spieß, and the JDA contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.dv8tion.jda.test.data;

import net.dv8tion.jda.api.exceptions.DataObjectParsingException;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

public class DataObjectTest {
    @Test
    void testMissingKeyException() {
        DataObject data = DataObject.empty()
                .put("foo", 1)
                .put("nested_object", DataObject.empty().put("test", "test value"))
                .put("nested_array", DataArray.empty().add("test value"));

        assertThatExceptionOfType(DataObjectParsingException.class)
                .isThrownBy(() -> data.get("bar"))
                .satisfies(exception -> {
                    assertThat(exception.getData()).isEqualTo(data);
                    String[] lines = exception.getMessage().split("\n", 2);
                    assertThat(lines[0]).isEqualTo("Missing value for key 'bar' with expected type any");
                    DataObject shallow = DataObject.fromJson(lines[1]);
                    assertThat(shallow.getInt("foo")).isEqualTo(1);
                    assertThat(shallow.getString("nested_object")).isEqualTo("{…truncated object…}");
                    assertThat(shallow.getString("nested_array")).isEqualTo("[…truncated array…]");
                });
    }

    @Test
    void testUnexpectedNullException() {
        DataObject data = DataObject.empty()
                .put("foo", null)
                .put("nested_object", DataObject.empty().put("test", "test value"))
                .put("nested_array", DataArray.empty().add("test value"));

        assertThatExceptionOfType(DataObjectParsingException.class)
                .isThrownBy(() -> data.getInt("foo"))
                .satisfies(exception -> {
                    assertThat(exception.getData()).isEqualTo(data);
                    String[] lines = exception.getMessage().split("\n", 2);
                    assertThat(lines[0]).isEqualTo("Unable to resolve value with key 'foo' to type int: null");
                    DataObject shallow = DataObject.fromJson(lines[1]);
                    assertThat(shallow.isNull("foo")).isTrue();
                    assertThat(shallow.getString("nested_object")).isEqualTo("{…truncated object…}");
                    assertThat(shallow.getString("nested_array")).isEqualTo("[…truncated array…]");
                });
    }
}
