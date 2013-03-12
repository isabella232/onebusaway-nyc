package org.onebusaway.nyc.report_archive.queue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.AnnotationIntrospector;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.xc.JaxbAnnotationIntrospector;
import org.onebusaway.container.refresh.Refreshable;
import org.onebusaway.nyc.queue.QueueListenerTask;
import org.onebusaway.nyc.queue.model.RealtimeEnvelope;
import org.onebusaway.nyc.report_archive.impl.CcLocationCache;
import org.onebusaway.nyc.report_archive.model.CcLocationReportRecord;
import org.onebusaway.nyc.report_archive.services.CcLocationReportDao;
import org.onebusaway.nyc.report_archive.services.EmergencyStatusNotificationService;
import org.onebusaway.nyc.report_archive.services.RecordValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

public class ArchivingInputQueueListenerTask extends QueueListenerTask {

  public static final int DELAY_THRESHOLD = 10 * 1000;
  private static final long SAVE_THRESHOLD = 500; //milliseconds, report if save takes longer than this
  protected static Logger _log = LoggerFactory.getLogger(ArchivingInputQueueListenerTask.class);
  
  private RecordValidationService validationService;

  /**
   * number of inserts to batch together
   */
  private int _batchSize;

  public void setBatchSize(String batchSizeStr) {
    _batchSize = Integer.decode(batchSizeStr);
  }
  
  private EmergencyStatusNotificationService emergencyStatusNotificationService;

  private CcLocationCache _ccLocationCache;

  @Autowired
  public void setCcLocationCache(CcLocationCache cache) {
    _ccLocationCache = cache;
  }
  
  @Autowired
  public void setValidationService(RecordValidationService validationService) {
	  this.validationService = validationService;
  }

  private long _lastCommitTime = System.currentTimeMillis();
  private long _commitTimeout = 1 * 1000; // 1 second by default

  /**
   * Time in milliseconds to give up waiting for data and commit current batch.
   * 
   * @param commitTimeout number of milliseconds to wait
   */
  public void setCommitTimeout(String commitTimeout) {
    _commitTimeout = Integer.decode(commitTimeout);
  }

  @Autowired
  private CcLocationReportDao _dao;
  
  // offset of timezone (-04:00 or -05:00)
  private String _zoneOffset = null;
  private String _systemTimeZone = null;
  private int _batchCount = 0;
  private List<CcLocationReportRecord> reports = Collections.synchronizedList(new ArrayList<CcLocationReportRecord>());

  public ArchivingInputQueueListenerTask() {
    /*
     * use Jaxb annotation interceptor so we pick up autogenerated annotations
     * from XSDs
     */
    AnnotationIntrospector jaxb = new JaxbAnnotationIntrospector();
    _mapper.getDeserializationConfig().setAnnotationIntrospector(jaxb);

  }

  public RealtimeEnvelope deserializeMessage(String contents) {
    RealtimeEnvelope message = null;
    try {
      JsonNode wrappedMessage = _mapper.readValue(contents, JsonNode.class);
      String ccLocationReportString = wrappedMessage.get("RealtimeEnvelope").toString();

      message = _mapper.readValue(ccLocationReportString,
          RealtimeEnvelope.class);
    } catch (Exception e) {
      _log.warn("Received corrupted message from queue; discarding: "
          + e.getMessage());
      _log.warn("Contents: " + contents);
    }
    return message;
  }

  @Refreshable(dependsOn = {
      "inference-engine.inputQueueHost", "inference-engine.inputQueuePort",
      "inference-engine.inputQueueName"})
  public void startListenerThread() {
    if (this._initialized) {
      _log.warn("Configuration service reconfiguring inference input queue service");
    }

    String host = getQueueHost();
    String queueName = getQueueName();
    Integer port = getQueuePort();

    if (host == null || queueName == null || port == null) {
      _log.info("Inference input queue is not attached; input hostname was not available via configuration service.");
      return;
    }

    _log.info("realtime archive listening on " + host + ":" + port + ", queue="
        + queueName);
    try {
      initializeQueue(host, queueName, port);
    } catch (InterruptedException ie) {
      return;
    }
  }

  @Override
  public String getQueueHost() {
    return _configurationService.getConfigurationValueAsString(
        "inference-engine.inputQueueHost", null);
  }

  @Override
  public String getQueueName() {
    return _configurationService.getConfigurationValueAsString(
        "inference-engine.inputQueueName", null);
  }

  public String getQueueDisplayName() {
    return "archive_realtime";
  }

  @Override
  public Integer getQueuePort() {
    return _configurationService.getConfigurationValueAsInteger(
        "inference-engine.inputQueuePort", 5563);
  }

  @Override
  // this method can't throw exceptions or it will stop the queue
  // listening
  public boolean processMessage(String address, String contents) {
	  RealtimeEnvelope envelope = null;
	  CcLocationReportRecord record = null;
	  try {
		  envelope = deserializeMessage(contents);

		  if (envelope == null || envelope.getCcLocationReport() == null) {
			  _log.error("Message discarded, probably corrupted, contents= "
					  + contents);
			  Exception e = new Exception(
					  "deserializeMessage failed, possible corrupted message.");
			  _dao.handleException(contents, e, null);
			  return false;
		  }

		  boolean validEnvelope = validationService.validateRealTimeRecord(envelope);
		  if(validEnvelope) {
			  record = new CcLocationReportRecord(envelope, contents, getZoneOffset());
			  _ccLocationCache.put(record);
		  } else {
			  long vehicleId = envelope.getCcLocationReport().getVehicle().getVehicleId();
			  _log.error("Discarding real time record for vehicle : {} as it does not meet the " +
				  		"required database constraints", vehicleId);
			  Exception e = new Exception("Real time record for vehile : " +vehicleId + " failed validation." +
			  		"Discarding");
			  _dao.handleException(contents, e, new Date());
		  }
		  if (record != null) {
			  _batchCount++;
			  // update cache for operational API
			  reports.add(record);

			  //Process record for emergency status
			  emergencyStatusNotificationService.process(record);

			  long batchWindow = System.currentTimeMillis() - _lastCommitTime;
			  if (_batchCount >= _batchSize || batchWindow > _commitTimeout) {
				  try {
					  // unfortunately save needs to be called here to allow transactional
					  // semantics to work
				    long saveWindow = System.currentTimeMillis();
					  _dao.saveOrUpdateReports(reports.toArray(new CcLocationReportRecord[0]));
					  long saveTime = System.currentTimeMillis() - saveWindow;
					  if (saveTime > SAVE_THRESHOLD)
					  _log.info("bhs save took " + saveTime + " ms");
				  } finally {
					  reports.clear();
					  _batchCount = 0;
					  _lastCommitTime = System.currentTimeMillis();
				  }
			  }

		  }
		  // re-calculate zoneOffset periodically
		  if (_batchCount == 0) {
			  _zoneOffset = null;
			  if (record != null) {
				  long delta = System.currentTimeMillis()
						  - record.getTimeReceived().getTime();
				  if (delta > DELAY_THRESHOLD) {
					  _log.warn("realtime queue is " + (delta / 1000) 
							  + " seconds behind with cache_size=" + _ccLocationCache.size());
				  }
			  }
		  }
	  } catch (Throwable t) {
		  _log.error("Exception processing contents= " + contents, t);
		  try {
			  Date timeReceived = null;
			  if (envelope != null)
				  timeReceived = new Date(envelope.getTimeReceived());
			  _dao.handleException(contents, t, timeReceived);
		  } catch (Throwable tt) {
			  // we tried
			  _log.error("Exception handling exception= " + tt);
		  }
	  }

	  return true;
  }

 

@PostConstruct
  public void setup() {
    super.setup();
    // make parsing lenient
    _mapper.configure(DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES,
        false);
    // set a reasonable default
    _systemTimeZone = _configurationService.getConfigurationValueAsString(
        "archive.systemTimeZone", "America/New_York");
  }

  @PreDestroy
  public void destroy() {
    super.destroy();
  }

  /**
   * Return the offset in an tz-offset string fragment ("-04:00" or "-5:00")
   * based on the daylight savings rules in effect during the given date. This
   * method assumes timezone is a standard hour boundary away from GMT.
   * 
   * Package private for unit tests.
   * 
   * @param date when to consider the zoneoffset. Now makes the most sense, but
   *          can be historical/future for unit testing
   * @param systemTimeZone the java string representing a timezone, such as
   *          "America/New_York"
   */
  String getZoneOffset(Date date, String systemTimeZone) {
    if (date == null)
      return null;
    // cache _zoneOffset
    if (_zoneOffset == null) {
      long millisecondOffset;
      // use systemTimeZone if available
      if (systemTimeZone != null) {
        millisecondOffset = TimeZone.getTimeZone(systemTimeZone).getOffset(
            date.getTime());
      } else {
        // use JVM default otherwise
        millisecondOffset = TimeZone.getDefault().getOffset(date.getTime());
      }
      String plusOrMinus = (millisecondOffset <= 0 ? "-" : "+");
      if (millisecondOffset == 0) {
        _zoneOffset = plusOrMinus + "00:00";
      } else {
        // format 1st arg 0-padded to a width of 2
        _zoneOffset = plusOrMinus
            + String.format("%1$02d",
                Math.abs(millisecondOffset / (1000 * 60 * 60))) + ":00";
      }
    }
    return _zoneOffset;

  }

  private String getZoneOffset() {
    return getZoneOffset(new Date(), _systemTimeZone);
  }

  /**
	* @param emergencyStatusNotificationService the emergencyStatusNotificationService to set
	*/
  @Autowired
  public void setEmergencyStatusNotificationService(
		  EmergencyStatusNotificationService emergencyStatusNotificationService) {
	this.emergencyStatusNotificationService = emergencyStatusNotificationService;
 }

}
