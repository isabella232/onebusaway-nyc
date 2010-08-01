/**
 * Copyright 2010, OpenPlans Licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.onebusaway.nyc.webapp.actions;

import static org.junit.Assert.*;

import org.junit.Test;

public class IndexActionTest {

  @Test
  public void testExecute() {
    IndexAction action = new IndexAction();
    action.execute();
    assertEquals("message set in execute", "Hello one bus away NY!",
        action.getMessage());
  }

}
