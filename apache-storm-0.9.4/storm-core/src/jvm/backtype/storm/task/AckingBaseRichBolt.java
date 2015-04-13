package backtype.storm.task;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import backtype.storm.generated.Grouping;
import backtype.storm.generated.StreamInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import backtype.storm.spout.ShellSpout;
import backtype.storm.testing.AckTracker;
import backtype.storm.topology.AckingOutputFieldsDeclarer;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;
import backtype.storm.utils.RotatingMap;
import backtype.storm.utils.Utils;
import backtype.storm.task.TopologyContextConstants.Configuration;

public abstract class AckingBaseRichBolt extends BaseRichBolt {

	private static final long serialVersionUID = 1L;
	
	public static Logger LOG = LoggerFactory.getLogger(ShellSpout.class);

	private static final Integer ROTATING_MAP_BUCKET_SIZE = 3;
	private static final String ACK_MESSAGE_DELIMITER = "_";
	private static final String TIMEOUTS_SEPARATOR = "|";
	private static final String ACK_STREAM_SEPARATOR = "|";
	private static final String TIMEOUT_DELIMITER = "_";
	private static final int ACK_MESSAGE_TOKEN_LENGTH = 5;
	private static final String ACK_MESSAGE_START_TOKEN = "ack_";

	private static final String TUPLE_ID_SEPARATOR = "_";
	private static final int ACTUAL_MESSAGE_INDEX = 1;
	private static final int TUPLE_ID_INDEX = 1;
	private static String TUPLE_ID_FIELD_NAME = "_tupleId";
	
	//TODO: are all of these thread safe? we should be able to support a parallelism > 1
	private OutputCollector collector_;
	private String componentId_ = new String();
	private Random rand = new Random(Integer.MAX_VALUE);
	private long lastRotate_ = System.currentTimeMillis();
	private Long defaultPerEdgeTimeout_;
	private Boolean enableStormDefaultTimeout_;
	private TopologyContext context_;
	
	// since this is just constructed once and read everywhere else, it should
	// be fine even in case of multi threaded environment
	// This is the list of all the streams on which we are supposed to send
	// Ack's
	private HashSet<String> sendAckStream_ = new HashSet<String>();
	
	// TODO: not sure if the operations are all threadsafe, which is why I am using concurrent hashmap
	// <timeout vs <tupleId, OriginalTuple>> 
	private ConcurrentHashMap<Long, RotatingMap<String, Tuple>> ackTracker_ = new ConcurrentHashMap<Long, RotatingMap<String, Tuple>>();
	
	// since this is just constructed once and read everywhere else, it should
	// be fine even in case of multi threaded environment
	// This contains timeout information of all the streams that are sent from
	// this Bolt
	private HashMap<TimeoutIdentifier, Long> timeouts_ = new HashMap<TimeoutIdentifier, Long>();
	
	public final void prepare(Map conf, TopologyContext context, OutputCollector collector) {
		
		collector_ = collector;
		componentId_ = context.getThisComponentId();
		defaultPerEdgeTimeout_ = context.getDefaultPerEdgeTimeout();
		enableStormDefaultTimeout_ = context.enableStormDefaultTimeoutMechanism();
		context_ = context;
		
		updateTimeouts(conf.get(Configuration.timeout.name()));
		updateAckStreams(conf.get(Configuration.send_ack.name()));
		
		createAckTrackersPerTimeout();
		
		customPrepare(conf, context, collector);
		
	}

	public abstract void customPrepare(Map conf, TopologyContext context, OutputCollector collector);

	private final String getTupleId(String sourceId, String targetId, String streamId) {
		return new StringBuilder().append(rand.nextInt())
				.append(TUPLE_ID_SEPARATOR).append(sourceId)
				.append(TUPLE_ID_SEPARATOR).append(targetId)
				.append(TUPLE_ID_SEPARATOR).append(streamId).toString();
	}
	
	private final String getTupleId(String sourceId, String targetId) {
		return getTupleId(sourceId, targetId, Utils.DEFAULT_STREAM_ID);
	}
	
	public final void execute(Tuple tuple) {
		
		System.out.println("Received a tuple {" + tuple.getString(0) + "}, {" + tuple.getString(1) +"}");
		
		checkForTimedOutTuples();
		
		if(tuple.getValue(ACTUAL_MESSAGE_INDEX).toString().startsWith(ACK_MESSAGE_START_TOKEN)) {
			System.out.println("Received an Ack message {" + tuple.getValue(ACTUAL_MESSAGE_INDEX).toString() +"}");
			handleAckMessage(tuple);
			return;
		}
		
		sendAckMessage(tuple);

		customExecute(tuple);

		// If the user decides to use Storm's default timeout mechanism, then
		// ack the tuple in Storm way
		if(enableStormDefaultTimeout_) {
			collector_.ack(tuple);
		}
	}

	/**
	 * Sends ack messages to all the preceding components which are tracking the
	 * Tuples based on timeout
	 */
	private void sendAckMessage(Tuple tuple) {
		// we need to emit the ack only on that particular stream which is
		// responsible for sending this message.
		String ackingStreamId = componentId_ + ACK_MESSAGE_DELIMITER
				+ tuple.getSourceComponent() + ACK_MESSAGE_DELIMITER
				+ tuple.getSourceStreamId();
		
		// ack message will be like ack_tupleId_componentId_streamID
		// TODO: RK Note, here we are assuming that tuple.getValue(0) will have our
		// ack message which has our tupleID
		StringBuilder ackMsg = new StringBuilder().append(
				ACK_MESSAGE_START_TOKEN).append(tuple.getValue(TUPLE_ID_INDEX).toString());

		// this is to make sure that all messages that we send from an
				// AckingBolt has the first tuple field as tupleId
		String tupleId = getTupleId(componentId_, tuple.getSourceComponent(), ackingStreamId);
		Values vals = new Values(tupleId);
		vals.add(ackMsg.toString());
		collector_.emit(ackingStreamId, vals);
		System.out.println("Acking the tuple with ID {" + tupleId +"} on stream {" + ackingStreamId +"}, msg {" + ackMsg +"}");
		LOG.info("Acking the tuple with ID {" + tupleId +"} on stream {" + ackingStreamId +"}");
	}

	/**
	 * This method takes an Ack message and updates the {@link AckTracker}
	 * accordingly
	 */
	private void handleAckMessage(Tuple tuple) {
		
		// this will be of form ack_tupleId_componentId_streamID
		// we are assuming that the tuple[0] will have the tuple ID, 
		// we will not use tupleId for acking messages. 
		String ack = tuple.getValue(ACTUAL_MESSAGE_INDEX).toString();
		String[] ackToks = ack.split("[*"+ACK_MESSAGE_DELIMITER+"*]+");
		
		System.out.println("In handleAckMessage");
		if(ACK_MESSAGE_TOKEN_LENGTH == ackToks.length) {
			String tupleKey = ack.substring(ack.indexOf("_")+1);
			findAndAckTuple(tupleKey);
		}
		System.out.println("Exiting handleAckMessage");
		
	}

	// this checks if there are any tuples which are not acked within the
	// specified timeout
	// and then explicitly fails them in Storm way falling back to Storm's
	// timeout/replay mechanism
	private void checkForTimedOutTuples() {
		// TODO: can this be done in a separate thread which runs for every 
		// min(perStreamTimeouts) seconds?
		System.out.println("In checkForTimedOutTuples");
		StringBuilder sb = new StringBuilder().append("Ack Trackers are :");
		for(Long timeout : ackTracker_.keySet()) {
			sb.append(timeout).append(",");
		}
		System.out.println(sb.toString());
		
		for(Long timeout : ackTracker_.keySet()) {
			long now = System.currentTimeMillis();
			if(now - lastRotate_ > timeout) {
				Map<String, Tuple> failed = ackTracker_.get(timeout).rotate();
				if(failed.isEmpty()) {
					System.out.println("No failed Tuples in this Bucket !!!");
				}
                for(String failedTuple : failed.keySet()) {
                	if(enableStormDefaultTimeout_) {
                		System.out.println("Tuple {" + failedTuple + "} has failed to get an acknowledgement on time !!!");
                		LOG.error("Tuple {" + failedTuple + "} has failed to get an acknowledgement on time !!!");
                		collector_.fail(failed.get(failedTuple));
                	} // else we can just ignore acking/failing tuples 
                }
			}
			lastRotate_ = now;
		}
		System.out.println("exiting checkForTimedOutTuples");
	}

	/**
	 * This needs to be implemented by the specific user of this @AckingBaseRichBolt
	 */
	public abstract void customExecute(Tuple tuple);
	
	public void emitTupleOnStream(Tuple tuple, Values values, String streamId) {
		for(String targetId : getTargetsForStream(streamId)) {
			String tupleId = getTupleId(componentId_, targetId, streamId);
			Values newVals = new Values(tupleId);
			newVals.addAll(values);
			if(enableStormDefaultTimeout_) {
				// TODO RK NOTE: we are acking the tuples if enableStormDefaultTimeout_
				// is true in execute() method
				collector_.emit(streamId, tuple, newVals);
				System.out.println("Emitting tuple {" + tupleId + "} on {" + streamId +"}");
				LOG.debug("Emitting tuple {" + tupleId + "} on {" + streamId +"}");
			} else {
				System.out.println("Emitting tuple {" + tupleId + "} on { default stream }");
				LOG.debug("Emitting tuple {" + tupleId + "} on { default stream }");
				collector_.emit(streamId, newVals);
			}

			TimeoutIdentifier ti = new TimeoutIdentifier(componentId_, targetId, streamId);
			Long timeout = timeouts_.containsKey(ti) ? timeouts_.get(ti) : defaultPerEdgeTimeout_; 
			ackTracker_.get(timeout).put(tupleId, tuple);
		}
		
	}
	
	public void emitTuple(Tuple tuple, Values values) {
		emitTupleOnStream(tuple, values, Utils.DEFAULT_STREAM_ID);
	}

	private HashSet<String> getTargetsForStream(String streamId) {
		Map<String, Map<String, Grouping>> targets = context_.getThisTargets();
		HashSet<String> ret = new HashSet<String>();
		if(targets.containsKey(streamId)) {
			ret.addAll(targets.get(streamId).keySet());
		}
		return ret;
	}
	
	public final void declareOutputFields(OutputFieldsDeclarer declarer) {
		AckingOutputFieldsDeclarer tempDeclarer = new AckingOutputFieldsDeclarer();
		customDeclareOutputFields(tempDeclarer);
		
		Map<String, StreamInfo> fieldsMap = tempDeclarer.getFieldsDeclaration();
		for(String strId : fieldsMap.keySet()) {
			// we are adding a tupleId field for all the streamIds that are
			// possibly used by this component.
			List<String> newFields = fieldsMap.get(strId).get_output_fields();
			newFields.add(TUPLE_ID_FIELD_NAME);
			declarer.declareStream(strId, fieldsMap.get(strId).is_direct(), new Fields(newFields));
		}
	}
	
	public abstract void customDeclareOutputFields(AckingOutputFieldsDeclarer declarer);
	
	private void findAndAckTuple(String tupleKey) {
		if(ackTracker_.isEmpty()) {
			System.out.println("findAndAckTuple trackers empty");
		}
		for(Long at : ackTracker_.keySet()) {
			RotatingMap<String, Tuple> rmap = ackTracker_.get(at);
			if(rmap.containsKey(tupleKey)) {
				System.out.println("Acking Tuple {" + tupleKey + "}");
				LOG.info("Acking Tuple {" + tupleKey + "}");
				rmap.remove(tupleKey);
			}
		}
		System.out.println("Exiting findAndAckTuple");
	}
	
	/**
	 * We know the total number of different possible timeouts for this
	 * component (including all the streams) in prepare method, we can use this
	 * information to dynamically create a set of {@link RotatingMap} instances
	 * which correspond to each of these timeouts.
	 */
	private void createAckTrackersPerTimeout() {
		for(TimeoutIdentifier i : timeouts_.keySet()) {
			if(!ackTracker_.containsKey(timeouts_.get(i))) {
				RotatingMap<String, Tuple> rmap = new RotatingMap<String, Tuple>(ROTATING_MAP_BUCKET_SIZE);
				ackTracker_.put(timeouts_.get(i), rmap);
				LOG.info("Created an Ack Tracker with timeout {" + timeouts_.get(i) + "}");
			}
		}
		
		// we should also add a tracker for default per edge timeouts.
		ackTracker_.put(defaultPerEdgeTimeout_, new RotatingMap<String, Tuple>(ROTATING_MAP_BUCKET_SIZE));
		LOG.info("Created an Ack Tracker with default timeout {" + defaultPerEdgeTimeout_ + "}");
	}
	
	private void updateTimeouts(Object timeouts) {
		if(timeouts == null) {
			LOG.info("There are no additional timeouts specified, will use the default per edge timeout !!!");
			return;
		}

		// all timeouts are of the form
		// key1_t1|key2_t2 ...
		// where key = sourceId+"_"+targetId+"_"+streamId;
		String[] timeoutsMap = ((String)timeouts).split("[*"+TIMEOUTS_SEPARATOR+"*]+");
		for(int i = 0; i < timeoutsMap.length; ++i) {
			String[] timeoutToks = timeoutsMap[i].split("[*"+TIMEOUT_DELIMITER+"*]+");
			if(timeoutToks.length >= 4) {
				try {
					timeouts_.put(new TimeoutIdentifier(timeoutToks[0],
							timeoutToks[1], timeoutToks[2]), 
							Long.parseLong(timeoutToks[timeoutToks.length-1]));
					LOG.info("Adding new per edge timeout {" + timeoutToks[timeoutToks.length-1] + "}");
				} catch (Exception e) {
				}
			}
		}
	}
	
	private void updateAckStreams(Object ackStreams) {
		if(ackStreams == null) {
			LOG.info("There are no Ack Streams, we should be good !!!");
			return;
		}
		// all ack streams are of the form
		// key1 | key2 | ....
		// where key = targetId+"_"+boltId+"_"+streamId
		String[] ackStreamArray = ((String)ackStreams).split("[*"+ACK_STREAM_SEPARATOR+"*]+");
		for(int i = 0; i < ackStreamArray.length; ++i) {
			if(!ackStreamArray[i].trim().isEmpty()) {
				sendAckStream_.add(ackStreamArray[i].trim());
				LOG.info("Adding Ack Stream {" + ackStreamArray[i].trim() +"}");
			}
		}
	}
	
}
