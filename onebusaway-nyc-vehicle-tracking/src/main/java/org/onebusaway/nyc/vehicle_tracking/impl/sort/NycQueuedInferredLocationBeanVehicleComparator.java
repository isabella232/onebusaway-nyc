/**
 * Copyright (c) 2011 Metropolitan Transportation Authority
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.onebusaway.nyc.vehicle_tracking.impl.sort;

import org.onebusaway.nyc.transit_data.model.NycQueuedInferredLocationBean;
import org.apache.commons.lang.builder.CompareToBuilder;

import java.util.Comparator;

public class NycQueuedInferredLocationBeanVehicleComparator implements
    Comparator<NycQueuedInferredLocationBean> {

  @Override
  public int compare(NycQueuedInferredLocationBean o1,
		  NycQueuedInferredLocationBean o2) {
    return new CompareToBuilder().append(o1.getVehicleId(), o2.getVehicleId()).toComparison();
  }
}
