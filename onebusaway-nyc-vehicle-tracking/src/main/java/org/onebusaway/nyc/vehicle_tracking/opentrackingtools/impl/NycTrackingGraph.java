package org.onebusaway.nyc.vehicle_tracking.opentrackingtools.impl;

import org.onebusaway.container.refresh.Refreshable;
import org.onebusaway.geospatial.model.CoordinatePoint;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.gtfs.services.calendar.CalendarService;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.BlockStateService;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.BlocksFromObservationService;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.JourneyStateTransitionModel;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.Observation;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.VehicleStateLibrary;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.likelihood.DscLikelihood;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.likelihood.NullStateLikelihood;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.likelihood.RunLikelihood;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.likelihood.RunTransitionLikelihood;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.likelihood.ScheduleLikelihood;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.state.BlockState;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.state.BlockStateObservation;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.state.JourneyState;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.state.MotionState;
import org.onebusaway.transit_data_federation.impl.RefreshableResources;
import org.onebusaway.transit_data_federation.model.ShapePoints;
import org.onebusaway.transit_data_federation.services.ExtendedCalendarService;
import org.onebusaway.transit_data_federation.services.FederatedTransitDataBundle;
import org.onebusaway.transit_data_federation.services.blocks.BlockCalendarService;
import org.onebusaway.transit_data_federation.services.blocks.BlockIndexService;
import org.onebusaway.transit_data_federation.services.blocks.BlockInstance;
import org.onebusaway.transit_data_federation.services.blocks.InstanceState;
import org.onebusaway.transit_data_federation.services.otp.OTPConfigurationService;
import org.onebusaway.transit_data_federation.services.shapes.ShapePointService;
import org.onebusaway.transit_data_federation.services.transit_graph.BlockConfigurationEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.BlockEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.BlockTripEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.TransitGraphDao;
import org.onebusaway.transit_data_federation.services.transit_graph.TripEntry;

import com.google.common.base.Preconditions;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.vividsolutions.jcs.precision.GeometryPrecisionReducer;
import com.vividsolutions.jcs.precision.NumberPrecisionReducer;
import com.vividsolutions.jts.algorithm.LineIntersector;
import com.vividsolutions.jts.algorithm.RobustLineIntersector;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.CoordinateList;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.PrecisionModel;
import com.vividsolutions.jts.index.strtree.SIRtree;
import com.vividsolutions.jts.index.strtree.STRtree;
import com.vividsolutions.jts.linearref.LengthIndexedLine;
import com.vividsolutions.jts.noding.IntersectionAdder;
import com.vividsolutions.jts.noding.MCIndexNoder;
import com.vividsolutions.jts.noding.NodedSegmentString;
import com.vividsolutions.jts.noding.SegmentStringDissolver;
import com.vividsolutions.jts.noding.SegmentStringDissolver.SegmentStringMerger;
import com.vividsolutions.jts.simplify.DouglasPeuckerSimplifier;

import org.geotools.geometry.jts.JTS;
import org.geotools.geometry.jts.JTSFactoryFinder;
import org.geotools.graph.build.line.DirectedLineStringGraphGenerator;
import org.geotools.graph.structure.basic.BasicDirectedEdge;
import org.opengis.referencing.operation.TransformException;
import org.opentrackingtools.graph.GenericJTSGraph;
import org.opentrackingtools.graph.InferenceGraphEdge;
import org.opentrackingtools.paths.PathState;
import org.opentrackingtools.util.GeoUtils;
import org.opentripplanner.routing.services.GraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.PostConstruct;

@Component
public class NycTrackingGraph extends GenericJTSGraph {

  /**
   * A little helper class that holds info about the trip geometries after
   * they're broken down into non-overlapping/intersecting lines (noded lines).
   * 
   * @author bwillard
   * 
   */
  public static class SegmentInfo {

    final private Boolean isSameOrientation;
    final private AgencyAndId shapeId;
    final private int geomNum;

    public SegmentInfo(AgencyAndId shapeId, int geomNum,
        boolean isSameOrientation) {
      this.shapeId = shapeId;
      this.isSameOrientation = isSameOrientation;
      this.geomNum = geomNum;
    }

    public AgencyAndId getShapeId() {
      return this.shapeId;
    }

    public Boolean getIsSameOrientation() {
      return this.isSameOrientation;
    }

    public int getGeomNum() {
      return geomNum;
    }

  }

  public class BlockTripEntryAndDate {

    final private BlockTripEntry blockTripEntry;
    final private Date serviceDate;

    public BlockTripEntryAndDate(BlockTripEntry entry, Date serviceDate) {
      this.blockTripEntry = entry;
      this.serviceDate = serviceDate;
    }

    public BlockTripEntry getBlockTripEntry() {
      return blockTripEntry;
    }

    public Date getServiceDate() {
      return serviceDate;
    }

  }

  private final Logger _log = LoggerFactory.getLogger(NycTrackingGraph.class);

  public ScheduleLikelihood schedLikelihood = new ScheduleLikelihood();

  public RunTransitionLikelihood runTransitionLikelihood = new RunTransitionLikelihood();

  public RunLikelihood runLikelihood = new RunLikelihood();

  @Autowired
  private GraphService _graphService;

  @Autowired
  private OTPConfigurationService _otpConfigurationService;

  @Autowired
  public FederatedTransitDataBundle _bundle;

  @Autowired
  public DscLikelihood dscLikelihood;

  @Autowired
  public NullStateLikelihood nullStateLikelihood;

  @Autowired
  private JourneyStateTransitionModel _journeyStateTransitionModel;

  @Autowired
  private BlocksFromObservationService _blocksFromObservationService;

  @Autowired
  private BlockStateService _blockStateService;

  @Autowired
  private ExtendedCalendarService _extCalendarService;

  @Autowired
  private BlockCalendarService _blockCalendarService;

  @Autowired
  private BlockIndexService _blockIndexService;

  @Autowired
  private TransitGraphDao _transitGraphDao;

  @Autowired
  private CalendarService _calendarService;

  @Autowired
  private ShapePointService _shapePointService;

  public static class TripInfo {
    final private Collection<SIRtree> _entries;
    final private Set<AgencyAndId> _shapeIds;

    public TripInfo(Set<AgencyAndId> geomShapeIds, Collection<SIRtree> entries) {
      _shapeIds = geomShapeIds;
      _entries = entries;
    }

    public Collection<SIRtree> getEntries() {
      return _entries;
    }

    public Collection<AgencyAndId> getShapeIds() {
      return _shapeIds;
    }

    public Set<BlockTripEntryAndDate> getActiveTrips(double timeFrom,
        double timeTo) {
      final Set<BlockTripEntryAndDate> activeTrips = Sets.newHashSet();
      for (final SIRtree tree : _entries) {
        activeTrips.addAll(tree.query(timeFrom, timeTo));
      }
      return activeTrips;
    }

  };

  @PostConstruct
  @Refreshable(dependsOn = {
      RefreshableResources.TRANSIT_GRAPH, RefreshableResources.NARRATIVE_DATA})
  public void setup() throws IOException, ClassNotFoundException {
    this.edgeIndex = new STRtree();
    this.graphGenerator = new DirectedLineStringGraphGenerator();
    buildGraph();
  }

  final private Multimap<AgencyAndId, LineString> _shapeIdToGeo = HashMultimap.create();

  final private Map<LineString, TripInfo> _geometryToTripInfo = Maps.newHashMap();

  private Random rng;

  private final Table<AgencyAndId, LineString, double[]> _lengthsAlongShapeMap = HashBasedTable.create();

  private void buildGraph() {
    try {
      _shapeIdToGeo.clear();
      _geometryToTripInfo.clear();
      _lengthsAlongShapeMap.clear();

      final double scale = 1d / 7d; // work within a 7m grid
      final GeometryFactory gf = // JTSFactoryFinder.getGeometryFactory();
      new GeometryFactory(new PrecisionModel(scale));
      final GeometryPrecisionReducer reducer = new GeometryPrecisionReducer(
          new NumberPrecisionReducer(scale));
      // final GeometryNoder gn = new GeometryNoder(gf.getPrecisionModel());

      final Map<String, Geometry> shapeIdToLines = Maps.newHashMap();
      final Map<Geometry, NodedSegmentString> lineToSegments = Maps.newHashMap();
      // TODO is there an easier/better way to get all these?
      final Set<AgencyAndId> shapeIds = Sets.newHashSet();
      for (final TripEntry trip : _transitGraphDao.getAllTrips()) {
        final AgencyAndId shapeId = trip.getShapeId();
        shapeIds.add(shapeId);
      }
      for (final AgencyAndId shapeId : shapeIds) {
        final ShapePoints shapePoints = _shapePointService.getShapePointsForShapeId(shapeId);
        if (shapePoints == null || shapePoints.isEmpty()) {
          _log.debug("shape with no shapepoints: " + shapeId);
          continue;
        }

        final CoordinateList coords = new CoordinateList();
        for (int i = 0; i < shapePoints.getSize(); ++i) {
          final Coordinate nextCoord = new Coordinate(shapePoints.getLats()[i],
              shapePoints.getLons()[i]);
          coords.add(nextCoord, false);
        }

        if (coords.isEmpty()) {
          _log.debug("shape with no length found: " + shapeId);
          continue;
        }

        final Geometry lineGeo = JTSFactoryFinder.getGeometryFactory().createLineString(
            coords.toCoordinateArray());

        Geometry euclidGeo = JTS.transform(lineGeo,
            GeoUtils.getTransform(lineGeo.getCoordinate()));
        euclidGeo = gf.createGeometry(reducer.reduce(euclidGeo));
        euclidGeo = DouglasPeuckerSimplifier.simplify(euclidGeo, 8);

        /*
         * These shapes can overlap themselves (i.e. not simple), so we need to
         * node them.
         */
        int geomNum = 0;
        double currentLength = 0d;

        final MCIndexNoder gn = new MCIndexNoder();
        LineIntersector li = new RobustLineIntersector();
        li.setPrecisionModel(gf.getPrecisionModel());
        gn.setSegmentIntersector(new IntersectionAdder(li));
        gn.computeNodes(Collections.singletonList(new NodedSegmentString(
            euclidGeo.getCoordinates(), null)));
        Coordinate prevCoord = null;
        for (final Object obj : gn.getNodedSubstrings()) {

          final NodedSegmentString nodedSubLine = (NodedSegmentString) obj;

          if (prevCoord != null)
            Preconditions.checkState(nodedSubLine.getCoordinate(0).equals(
                prevCoord));

          final LineString subLine = gf.createLineString(nodedSubLine.getCoordinates());

          subLine.setUserData(currentLength);

          nodedSubLine.setData(Lists.newArrayList(new SegmentInfo(shapeId,
              geomNum, true)));
          lineToSegments.put(subLine, nodedSubLine);
          shapeIdToLines.put(shapeId.toString() + "_" + geomNum, subLine);
          geomNum++;
          currentLength += subLine.getLength();
          prevCoord = nodedSubLine.getCoordinates()[nodedSubLine.size() - 1];
        }

      }
      _log.info("\tnoded ShapePoints=" + lineToSegments.size());

      // final MCIndexSnapRounder noder = new MCIndexSnapRounder(new
      // PrecisionModel(1d));
      final MCIndexNoder noder = new MCIndexNoder();
      final LineIntersector li = new RobustLineIntersector();
      li.setPrecisionModel(gf.getPrecisionModel());
      noder.setSegmentIntersector(new NycCustomIntersectionAdder(li));

      _log.info("\tcomputing nodes");
      noder.computeNodes(lineToSegments.values());

      /*
       * This merge method takes two segments for which one will be abandoned
       * due to it being a duplicate of the other. The one abandoned is the
       * second argument. We extend the line merge process by merging the
       * associated shape ids as well. Additionally, we need to know which
       * direction each segment is for each shape id.
       */
      final SegmentStringMerger merger = new NycCustomSegmentStringDissolver.NycCustomSegmentStringMerger();

      _log.info("\tdissolving nodes");
      final NycCustomSegmentStringDissolver dissolver = new NycCustomSegmentStringDissolver(scale, merger);
      dissolver.dissolve(noder.getNodedSubstrings());

      _log.info("\tdissolved lines=" + dissolver.getDissolved().size());
      for (final Object obj : dissolver.getDissolved()) {
        final NodedSegmentString segments = (NodedSegmentString) obj;
        if (segments.size() <= 1)
          continue;
        final LineString line = gf.createLineString(segments.getCoordinates());
        // line = (LineString) DouglasPeuckerSimplifier.simplify(line, 2);
        LineString lineReverse = null;
        if (line.getLength() < 1d)
          continue;
        for (final SegmentInfo si : (List<SegmentInfo>) segments.getData()) {
          LineString orientedSubLine;
          if (!si.getIsSameOrientation()) {
            if (lineReverse == null)
              lineReverse = (LineString) line.reverse();

            orientedSubLine = lineReverse;
          } else {
            orientedSubLine = line;
          }
          final Geometry sourceLine = shapeIdToLines.get(si.getShapeId() + "_"
              + si.getGeomNum());
          final LengthIndexedLine lil = new LengthIndexedLine(sourceLine);
          final double[] lengthIndices = sourceLine.equalsExact(orientedSubLine)
              ? new double[] {0d, orientedSubLine.getLength()}
              : Preconditions.checkNotNull(lil.indicesOf(orientedSubLine));
          /*
           * Adjust for the original segments start length along the shape.
           */
          final double distanceToStartOfShape = (Double) sourceLine.getUserData();
          lengthIndices[0] += distanceToStartOfShape;
          lengthIndices[1] += distanceToStartOfShape;

          _lengthsAlongShapeMap.put(si.getShapeId(), orientedSubLine,
              lengthIndices);
          _shapeIdToGeo.put(si.getShapeId(), orientedSubLine);
        }
      }
      _log.info("\tresult shapeIds=" + _shapeIdToGeo.keySet().size());

      final Set<AgencyAndId> missingShapeGeoms = Sets.newHashSet();
      _log.info("generating shapeId & blockConfig to block trips map...");
      for (final BlockEntry blockEntry : _transitGraphDao.getAllBlocks()) {
        for (final BlockConfigurationEntry blockConfig : blockEntry.getConfigurations()) {
          for (final BlockTripEntry blockTrip : blockConfig.getTrips()) {
            final TripEntry trip = blockTrip.getTrip();
            final AgencyAndId shapeId = trip.getShapeId();
            final AgencyAndId blockId = blockEntry.getId();

            if (shapeId != null) {
              if (!blockId.hasValues()) {
                _log.debug("trip with null block id: " + blockId);
                continue;
              }

              final Collection<LineString> shapesForId = _shapeIdToGeo.get(shapeId);
              if (shapesForId.isEmpty()) {
                missingShapeGeoms.add(shapeId);
                continue;
              }

              final SIRtree tree = buildTimeIndex(Collections.singletonList(blockTrip));

              for (final LineString lineGeo : shapesForId) {
                final TripInfo tripInfo = _geometryToTripInfo.get(lineGeo);
                if (tripInfo == null) {
                  final List<SIRtree> entries = Lists.newArrayList(tree);
                  final Set<AgencyAndId> geomShapeIds = Sets.newHashSet(shapeId);
                  _geometryToTripInfo.put(lineGeo, new TripInfo(geomShapeIds,
                      entries));
                } else {
                  tripInfo.getEntries().add(tree);
                  tripInfo.getShapeIds().add(shapeId);
                }

                // DEBUG
                Preconditions.checkState(this._lengthsAlongShapeMap.contains(
                    shapeId, lineGeo));
              }
            }
          }
        }
      }

      if (missingShapeGeoms.size() > 0) {
        _log.debug(missingShapeGeoms.size()
            + " shape(s) with no geom mapping: " + missingShapeGeoms);
      }

      _log.info("\ttripInfo=" + _geometryToTripInfo.size());

      this.createGraphFromLineStrings(_geometryToTripInfo.keySet(), false);

    } catch (final TransformException ex) {
      ex.printStackTrace();
    }

    _log.info("done.");
  }

  private NycTrackingGraph() {
  }

  public BlockStateObservation getBlockStateObs(@Nonnull
  Observation obs, @Nonnull
  PathState pathState, @Nullable
  BlockTripEntry blockTripEntry, long serviceDate) {

    final BlockState blockState;

    if (pathState.isOnRoad()) {

      Preconditions.checkNotNull(blockTripEntry);

      BlockTripEntry newBlockTripEntry = blockTripEntry;
      final AgencyAndId shapeId = blockTripEntry.getTrip().getShapeId();
      final InferenceGraphEdge edge = pathState.getEdge().getInferenceGraphSegment();
      double[] lengthAlongShape = this._lengthsAlongShapeMap.get(shapeId,
          edge.getGeometry());
      /*
       * Fix this. We should know exactly which trip/shape before this, right?
       */
      if (lengthAlongShape == null) {
        newBlockTripEntry = Preconditions.checkNotNull(blockTripEntry.getNextTrip());
        lengthAlongShape = this._lengthsAlongShapeMap.get(
            newBlockTripEntry.getTrip().getShapeId(),
            pathState.getEdge().getInferenceGraphSegment().getGeometry());
      }

      double distanceAlongBlock = newBlockTripEntry.getDistanceAlongBlock()
          + lengthAlongShape[0]
          + pathState.getEdge().getDistFromStartOfGraphEdge()
          + pathState.getEdgeState().getElement(0);

      /*
       * If we simplified the shapes, then distance along block will no longer
       * be exact, so we need to snap to the shape we intend to be on.
       */
      final double totalDistanceAlongBlockForShape = newBlockTripEntry.getDistanceAlongBlock()
          + newBlockTripEntry.getTrip().getTotalTripDistance() - 1d;
      if (distanceAlongBlock > totalDistanceAlongBlockForShape)
        distanceAlongBlock = totalDistanceAlongBlockForShape;

      Preconditions.checkState(distanceAlongBlock >= 0d);

      final InstanceState instState = new InstanceState(serviceDate);
      final BlockInstance instance = new BlockInstance(
          newBlockTripEntry.getBlockConfiguration(), instState);
      // _blockCalendarService.getBlockInstance(
      // blockTripEntry.getBlockConfiguration().getBlock().getId(),
      // serviceDate);

      blockState = _blockStateService.getAsState(instance, distanceAlongBlock);
    } else {
      blockState = null;
    }

    final boolean isAtPotentialLayoverSpot = VehicleStateLibrary.isAtPotentialLayoverSpot(
        blockState, obs);
    final BlockStateObservation blockStateObs;
    if (blockState != null) {
      blockStateObs = new BlockStateObservation(blockState, obs,
          isAtPotentialLayoverSpot, pathState.isOnRoad());
    } else {
      blockStateObs = null;
    }
    return blockStateObs;
  }

  public TripInfo getTripInfo(@Nonnull
  InferenceGraphEdge inferredEdge) {
    if (inferredEdge.isNullEdge())
      return null;
    final BasicDirectedEdge edge = (BasicDirectedEdge) inferredEdge.getBackingEdge();
    final LineString edgeGeom = (LineString) edge.getObject();
    // TODO this null check is temporary. remove when non-route streets are
    // included.
    final TripInfo tripInfo = Preconditions.checkNotNull(this._geometryToTripInfo.get(edgeGeom));
    return tripInfo;
  }

  public BlockCalendarService getBlockCalendarService() {
    return _blockCalendarService;
  }

  private SIRtree buildTimeIndex(Collection<BlockTripEntry> collection) {
    // TODO what's a good value for max nodes?
    final SIRtree blockTripTimeIndex = new SIRtree();
    for (final BlockTripEntry entry : collection) {
      for (final Date serviceDate : _calendarService.getDatesForLocalizedServiceId(entry.getTrip().getServiceId())) {
        final int maxDeparture = Iterables.getFirst(entry.getStopTimes(), null).getStopTime().getDepartureTime();
        final double fromTime = maxDeparture * 1000d + serviceDate.getTime();
        final int minArrival = Iterables.getLast(entry.getStopTimes()).getStopTime().getArrivalTime();
        final double toTime = minArrival * 1000d + serviceDate.getTime();
        blockTripTimeIndex.insert(fromTime, toTime, new BlockTripEntryAndDate(
            entry, serviceDate));
      }
    }
    blockTripTimeIndex.build();

    return blockTripTimeIndex;
  }

  public BlockState getBlockState(@Nonnull
  BlockInstance instance, double distanceAlongBlock) {
    return _blockStateService.getAsState(instance, distanceAlongBlock);
  }

  public Collection<BlockStateObservation> getBlockStatesFromObservation(
      @Nonnull
      Observation obs) {
    return _blocksFromObservationService.determinePotentialBlockStatesForObservation(obs);
  }

  public ScheduleLikelihood getSchedLikelihood() {
    return schedLikelihood;
  }

  public RunLikelihood getRunLikelihood() {
    return runLikelihood;
  }

  public RunTransitionLikelihood getRunTransitionLikelihood() {
    return runTransitionLikelihood;
  }

  public DscLikelihood getDscLikelihood() {
    return dscLikelihood;
  }

  public NullStateLikelihood getNullStateLikelihood() {
    return nullStateLikelihood;
  }

  public Random getRng() {
    return rng;
  }

  public void setRng(Random rng) {
    this.rng = rng;
  }

  public BlocksFromObservationService getBlocksFromObservationService() {
    return _blocksFromObservationService;
  }

  public BlockStateService getBlockStateService() {
    return _blockStateService;
  }

  public CalendarService getCalendarService() {
    return _calendarService;
  }

  public BlockIndexService getBlockIndexService() {
    return _blockIndexService;
  }

  public ExtendedCalendarService getExtCalendarService() {
    return _extCalendarService;
  }

  public org.onebusaway.nyc.vehicle_tracking.impl.inference.state.VehicleState createOldTypeVehicleState(
      @Nonnull
      Observation mtaObs,
      @Nullable
      BlockStateObservation blockStateObs,
      boolean vehicleHasNotMoved,
      @Nullable
      org.onebusaway.nyc.vehicle_tracking.impl.inference.state.VehicleState oldTypeParent) {

    final CoordinatePoint point;
    if (blockStateObs != null) {
      point = blockStateObs.getBlockState().getBlockLocation().getLocation();
    } else {
      point = mtaObs.getLocation();
    }
    final MotionState motionState = new MotionState(mtaObs.getTime(), point,
        vehicleHasNotMoved);

    final JourneyState journeyState = _journeyStateTransitionModel.getJourneyState(
        blockStateObs, oldTypeParent, mtaObs, vehicleHasNotMoved, false);

    final org.onebusaway.nyc.vehicle_tracking.impl.inference.state.VehicleState newOldTypeState = new org.onebusaway.nyc.vehicle_tracking.impl.inference.state.VehicleState(
        motionState, blockStateObs, journeyState, null, mtaObs);

    return newOldTypeState;
  }

  public JourneyStateTransitionModel getJourneyStateTransitionModel() {
    return _journeyStateTransitionModel;
  }

  public ShapePointService getShapePointService() {
    return _shapePointService;
  }

  public Multimap<AgencyAndId, LineString> getShapeIdToGeo() {
    return _shapeIdToGeo;
  }

  public Table<AgencyAndId, LineString, double[]> getLengthsAlongShapeMap() {
    return _lengthsAlongShapeMap;
  }

  // @Override
  // public Collection<InferenceGraphEdge> getOutgoingTransferableEdges(
  // InferenceGraphEdge infEdge) {
  // Collection<InferenceGraphEdge> result = Lists.newArrayList();
  // /*
  // * Since the intersections aren't known, i.e. the noding is wrong, we use
  // the
  // * simply disallow looping back on the source unless it's at the end of the
  // original shape.
  // * FIXME this is a temporary work-around.
  // */
  // Map<String, Object> sourceProperties = (Map<String, Object>)
  // ((Geometry)(infEdge.getGeometry().getUserData())).getUserData();
  // final boolean sourceEdgeAtEnd = (Boolean)
  // sourceProperties.get("isAtEndOfShape");
  // AgencyAndId shapeId = (AgencyAndId) sourceProperties.get("shapeId");
  // for (InferenceGraphEdge transferEdge :
  // super.getOutgoingTransferableEdges(infEdge)) {
  //
  // Map<String, Object> transferProperties = (Map<String, Object>)
  // ((Geometry)(transferEdge.getGeometry().getUserData())).getUserData();
  // AgencyAndId transferShapeId = (AgencyAndId)
  // transferProperties.get("shapeId");
  // if (!sourceEdgeAtEnd
  // && shapeId.equals(transferShapeId)
  // && transferEdge.getGeometry().reverse().equalsExact(infEdge.getGeometry()))
  // continue;
  // else
  // result.add(transferEdge);
  // }
  // return result;
  // }

}
