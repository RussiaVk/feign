/*
 * Copyright 2012-2023 The Feign Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign.jaxrs;

import feign.Param.Expander;
import jakarta.ws.rs.core.Cookie;

final class CookieParamExpander extends AbstractParameterValidator<Cookie> implements Expander {
  private String name;

  public CookieParamExpander(String name) {
    this.name = name;
  }

  @Override
  public String expand(Object value) throws IllegalArgumentException {

    if (!super.test(value)) {
      throw new IllegalArgumentException();
    }
    if (value instanceof Cookie) {
      String name = ((Cookie) value).getName();
      if (!this.name.equals(name)) {
        throw new IllegalArgumentException(String
            .format("The Cookie's name '%s' do not match with CookieParam's value '%s'!", name,
                this.name));
      }
      return ((Cookie) value).getValue();
    } else {
      return (String) value;
    }
  }
}
