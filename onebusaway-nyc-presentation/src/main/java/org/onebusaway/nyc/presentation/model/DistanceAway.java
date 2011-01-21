package org.onebusaway.nyc.presentation.model;

import java.util.Date;

import org.onebusaway.transit_data.model.trips.TripStatusBean;

/**
 * Data transfer object for how far away a vehicle is
 */
public class DistanceAway implements Comparable<DistanceAway> {
  private int atStopThresholdInFeet = 100;
  private int arrivingThresholdInFeet = 500;
  private int arrivingThresholdInStops = 0;
  private int showDistanceInStopsThresholdInStops = 3;
	
  private final int stopsAway;
  private final int feetAway;
  private final int staleTimeoutSeconds;
  private final Date timestamp;
  @SuppressWarnings("unused") // leaving in currentMode in case formatting rules depend on interface type in the future.
  private Mode currentMode;
  private FormattingContext displayContext;
  private TripStatusBean statusBean;
  
  public DistanceAway(int stopsAway, int feetAway, Date timestamp, Mode m, 
		  int staleTimeoutSeconds, FormattingContext displayContext, TripStatusBean statusBean) {
	  
    this.stopsAway = stopsAway;
    this.feetAway = feetAway;
    this.timestamp = timestamp;
    this.currentMode = m;
    this.staleTimeoutSeconds = staleTimeoutSeconds;
    this.displayContext = displayContext;
    this.statusBean = statusBean;
  }

  public int getStopsAway() {
    return stopsAway;
  }

  public Date getUpdateTimestamp() {
	    return timestamp;
  }

  public int getFeetAway() {
    return feetAway;
  }

  public String getPresentableDistance() {
	String r = "";
	
	// we're "at terminal" if vehicle is currently in layover at the end or start terminal
	// on the previous or current trip
	boolean atTerminal = false;
	if(statusBean != null) {
		String phase = statusBean.getPhase();
	
		if (phase != null && 
				(phase.toLowerCase().equals("layover_during") || phase.toLowerCase().equals("layover_before"))) {

			Double distanceAlongTrip = statusBean.getDistanceAlongTrip();
			Double totalDistanceAlongTrip = statusBean.getTotalDistanceAlongTrip();			
			if(distanceAlongTrip != null && totalDistanceAlongTrip != null) {
				Double ratio = distanceAlongTrip / totalDistanceAlongTrip;				
				if(ratio > .80 || ratio < .20) {
					atTerminal = true;
				}
			}
		}
	}
	
	if(feetAway <= atStopThresholdInFeet)
		r = "at stop";		
	else if(atTerminal == false 
			&& feetAway <= arrivingThresholdInFeet && stopsAway <= arrivingThresholdInStops)

		r = "approaching";
	else {
		if(stopsAway <= showDistanceInStopsThresholdInStops) {
			if(stopsAway == 0)
				  r = "< 1 stop away";
			else	  
				  r = stopsAway == 1
				  	? "1 stop away"
				  	: stopsAway + " stops away";			
		} else {
		  double milesAway = (float)feetAway / 5280;
		  r = String.format("%1.1f mi. away", milesAway);
		}
	}

	// if we're formatting a stop bubble, add "at terminal" if vehicle is currently at terminal.
	if (displayContext == FormattingContext.STOP && atTerminal == true) {
		r += " (at terminal)";
	}
	
	// old/stale data
	if(new Date().getTime() - timestamp.getTime() > 1000 * this.staleTimeoutSeconds) {
		r += " (old data)";
	}
	
	return r;
  }
  
  @Override
  public int hashCode() {
    return 31*feetAway + 31*stopsAway;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof DistanceAway))
      return false;
    DistanceAway rhs = (DistanceAway) obj;
    if (feetAway != rhs.feetAway)
      return false;
    if (stopsAway != rhs.stopsAway)
      return false;
    return true;
  }

  @Override
  public int compareTo(DistanceAway rhs) {
    if (feetAway < rhs.feetAway) return -1;
    if (feetAway > rhs.feetAway) return 1;
    if (stopsAway < rhs.stopsAway) return -1;
    if (stopsAway > rhs.stopsAway) return 1;
    return 0;
  }
}
