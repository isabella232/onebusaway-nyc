package org.onebusaway.nyc.queue_http_proxy;

import org.onebusaway.nyc.queue.IPublisher;
import org.onebusaway.nyc.queue_test.SimplePropertyNamingStrategy;

import org.codehaus.jackson.map.ObjectMapper;

import tcip_3_0_5_local.NMEA;
import tcip_final_3_0_5_1.CPTOperatorIden;
import tcip_final_3_0_5_1.CPTVehicleIden;
import tcip_final_3_0_5_1.CcLocationReport;
import tcip_final_3_0_5_1.SCHRouteIden;
import tcip_final_3_0_5_1.SCHRunIden;
import tcip_final_3_0_5_1.SPDataQuality;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;

import lrms_final_09_07.Angle;

public class TestThroughput implements Runnable {

  private static final double MIN_LAT = 40.221755;
  private static final double MAX_LAT = 40.780869;
  private static final double MIN_LONG = -74.252339;
  private static final double MAX_LONG = -73.388287;
  private static final int MIN_DSC = 0; // actually 1346
  private static final int MAX_DSC = 8000;
  private static final int MAX_VEHICLES = 8000;
  private static final int MAX_OPERATOR = 100000; // fake
  private static final int MIN_REQUEST = 1;
  private static final int MAX_REQUEST = 1286650;
  private static final int MIN_ROUTE = 1;
  private static final int MAX_ROUTE = 99;
  private static final int MIN_RUN = 0;
  private static final int MAX_RUN = 100000; // fake
  private static final int MIN_SPEED = -30;
  private static final int MAX_SPEED = 149;
  private ObjectMapper _ccmapper;

  private IPublisher _publisher;
  private int sends = 0;

  public TestThroughput(IPublisher publisher, int sends) {
    _publisher = publisher;
    this.sends = sends;
    setupMappers();
  }

  public void run() {
    int vehicleCount = 0;
    long timeStamp = System.currentTimeMillis();
    int sent = 0;
    while (!Thread.currentThread().isInterrupted()) {
      long now = System.currentTimeMillis();
      if ((now - timeStamp) > 1000) {
        // 1 second has passed, reset
        System.out.println("sent " + sent + " messages in " + (now - timeStamp)
            + " milliseconds.");
        sent = 0;
        timeStamp = System.currentTimeMillis();
      } else if (sent < sends) {
        // not throttled, send

        _publisher.send(wrap(createRealtimeMessage(vehicleCount, timeStamp)));
        sent++;
        vehicleCount++;
        if (vehicleCount > MAX_VEHICLES) {
          vehicleCount = 0;
        }
      } else {
        try {
          Thread.sleep(1);
        } catch (InterruptedException e) {
          // bury
        }
      }
    }

  }

  private byte[] wrap(String createRealtimeMessage) {
    // TODO Auto-generated method stub
    String msg = "{\"CcLocationReport\":" + createRealtimeMessage + "}";
    return msg.getBytes();
  }

  // TCIP is inconsistent in property naming, give hints to jackson for
  // serialization
  private void setupMappers() {
    _ccmapper = new ObjectMapper();
    HashMap<String, String> exceptions = new HashMap<String, String>();
    exceptions.put("destSignCode", "destSignCode");
    exceptions.put("operatorID", "operatorID");
    exceptions.put("routeID", "routeID");
    exceptions.put("runID", "runID");
    exceptions.put("recordTimestamp", "recordTimestamp");
    exceptions.put("nameLangs", "nameLangs");
    exceptions.put("designatorLangs", "designatorLangs");
    exceptions.put("agencydesignatorLangs", "agencydesignatorLangs");
    exceptions.put("cep90Percent", "cep-90-percent");
    exceptions.put("cep95Percent", "cep-95-percent");
    exceptions.put("cep99Percent", "cep-99-percent");
    exceptions.put("cep99Pt9Percent", "cep-99pt9percent");
    exceptions.put("tripDistance", "tripDistance");
    exceptions.put("routeDesignatorLangs", "route-designatorLangs");
    exceptions.put("routeNameLangs", "route-nameLangs");
    exceptions.put("blockID", "blockID");
    exceptions.put("emergencyCodes", "emergencyCodes");
    exceptions.put("localCcLocationReport", "localCcLocationReport");
    exceptions.put("nmea", "NMEA");
    _ccmapper.setPropertyNamingStrategy(new SimplePropertyNamingStrategy(
        exceptions));
  }

  private String createRealtimeMessage(int vehicleCount, long timeStamp) {
    CcLocationReport m = new CcLocationReport();
    m.setDataQuality(new SPDataQuality());
    m.getDataQuality().setQualitativeIndicator("4");
    m.setDestSignCode(new Long(distributeInt(MIN_DSC, MAX_DSC)));
    m.setDirection(new Angle());
    m.getDirection().setDeg(new BigDecimal(distributeDouble(0, 360)));
    m.setLatitude((int) Math.round(distributeDouble(MIN_LAT, MAX_LAT) * 1000000));
    m.setLongitude((int) Math.round(distributeDouble(MIN_LONG, MAX_LONG) * 1000000));
    m.setManufacturerData("VFTP123456789");
    m.setOperatorID(new CPTOperatorIden());
    m.getOperatorID().setOperatorId(0);
    m.getOperatorID().setDesignator("" + distributeInt(0, MAX_OPERATOR));
    m.setRequestId(distributeInt(MIN_REQUEST, MAX_REQUEST));
    m.setRouteID(new SCHRouteIden());
    m.getRouteID().setRouteId(0);
    m.getRouteID().setRouteDesignator("" + distributeInt(MIN_ROUTE, MAX_ROUTE));
    m.setRunID(new SCHRunIden());
    m.getRunID().setRunId(0);
    m.getRunID().setDesignator("" + distributeInt(MIN_RUN, MAX_RUN));
    m.setSpeed((short) distributeInt(MIN_SPEED, MAX_SPEED).intValue());
    m.setStatusInfo(0);
    m.setTimeReported(toDate(timeStamp));
    m.setVehicle(new CPTVehicleIden());
    m.getVehicle().setAgencydesignator("MTA NYCT");
    m.getVehicle().setAgencyId(2008l);
    m.getVehicle().setVehicleId(vehicleCount);
    m.setLocalCcLocationReport(new tcip_3_0_5_local.CcLocationReport());
    m.getLocalCcLocationReport().setNMEA(new NMEA());
    m.getLocalCcLocationReport().getNMEA().getSentence().add(
        "$GPRMC,105850.00,A,4038.445646,N,07401.094043,W,002.642,128.77,220611,,,A*7C");
    m.getLocalCcLocationReport().getNMEA().getSentence().add(
        "$GPGGA,105850.000,4038.44565,N,07401.09404,W,1,09,01.7,+00042.0,M,,M,,*49");
    // serialize to json
    try {
      return _ccmapper.writeValueAsString(m);
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }

  }

  private Double distributeDouble(double min, double max) {
    // generate random value between min and max
    return min + (int) (Math.random() * ((max - min) + 1));
  }

  private Integer distributeInt(int min, int max) {
    return (int) (min + (int) (Math.random() * ((max - min) + 1)));
  }

  private static final SimpleDateFormat dateFormatter = new SimpleDateFormat(
      "yyyy-MM-dd'T'HH:mm:ss.SSS-04:00");

  private String toDate(long timestamp) {
    return dateFormatter.format(new Date(timestamp));
  }

}