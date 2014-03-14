package org.onebusaway.nyc.admin.service.bundle.task;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.onebusaway.nyc.admin.model.BundleBuildRequest;
import org.onebusaway.nyc.admin.model.BundleRequestResponse;
import org.onebusaway.nyc.transit_data_federation.bundle.tasks.MultiCSVLogger;
import org.onebusaway.nyc.transit_data_manager.bundle.model.BundleMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

public class MetadataTask implements Runnable {

  private static Logger _log = LoggerFactory.getLogger(MetadataTask.class);
  @Autowired
  private MultiCSVLogger logger;
  @Autowired
  private BundleRequestResponse requestResponse;
  
  private ObjectMapper mapper = new ObjectMapper();
  
  public void setLogger(MultiCSVLogger logger) {
    this.logger = logger;
  }
  
  public void setBundleRequestResponse(BundleRequestResponse requestResponse) {
    this.requestResponse = requestResponse;
  }
  
  @Override
  public void run() {
    BundleMetadata data = new BundleMetadata(); 
    data.setId(generateId());
    data.setName(requestResponse.getRequest().getBundleName());
    data.setServiceDateFrom(requestResponse.getRequest().getBundleStartDate().toDate());
    data.setServiceDataTo(requestResponse.getRequest().getBundleEndDate().toDate());
    logger.changelog("generated metadata for bundle " + data.getName());
    String outputDirectory = requestResponse.getResponse().getBundleOutputDirectory();
    String outputFile = outputDirectory + File.separator + "metadata.json";
    try {
      mapper.writeValue(new File(outputFile), data);
    } catch (Exception e) {
      _log.error("json serialization failed:", e);
    }
  }

  private String generateId() {
    /*
    * this is not guaranteed to be unique but is good enough for this
    * occasional usage.  Purists can follow Publisher.java's pattern
    * in queue-subscriber.
    */ 
    return UUID.randomUUID().toString();
  }
}
