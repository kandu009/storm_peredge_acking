package storm.starter.faulttolerance.delayer.acking;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichSpout;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Values;
import backtype.storm.utils.Utils;

public class AckingDelayerRandomWordSpout extends BaseRichSpout {
	
	private static final long serialVersionUID = 1L;

	// RK NOTE: this should start the default storm ack tracker since we are
	// passing the tupleID as the message ID which will be used by storm as
	// a key to track the tuples progress.
	SpoutOutputCollector _collector;
	Random _rand;
	Boolean enableStormsTimeoutMechanism_;
	String outputStream_;
	
	private static final String[] sentences = new String[] {
			"ampere", "distributed", "eat", "barometer", "fun", "gift", "cadmium" };
	
	private HashMap<String, Integer> tupleTracker_ = new HashMap<String, Integer>();
	
	public AckingDelayerRandomWordSpout(String stream) {
		outputStream_ = stream;
	}

	@Override
	public void open(Map conf, TopologyContext context,
			SpoutOutputCollector collector) {
		_collector = collector;
		_rand = new Random();
		enableStormsTimeoutMechanism_ = context.enableStormDefaultTimeoutMechanism();
	}

	@Override
	public void nextTuple() {

		Utils.sleep(Math.abs(100));
		
		int index = _rand.nextInt(sentences.length);
		String sentence = sentences[index];
		String tupleId = new StringBuilder().append(_rand.nextInt()).toString();

		Values vals = new Values(tupleId);
		vals.add(sentence);

		if (enableStormsTimeoutMechanism_) {
			// RKNOTE
			// since we want Storm to track the tuples and its acks here
			// we need to give some messageId to emit (3rd argument).

			// But is this messageId needed in every bolt for acking?
			// or can we just not worry about it as long as the tuple is
			// anchored and acked/failed?
			_collector.emit(outputStream_, vals, tupleId);
		} else {
			_collector.emit(outputStream_, vals);
		}
		
		System.out.println("Emitting {" + tupleId + "} from Spout !");
		
		tupleTracker_.put(tupleId, index);

	}
	
	public void emitTuple(int index) {
		
		Utils.sleep(Math.abs(100));
		
		String sentence = sentences[index];
		String tupleId = new StringBuilder().append(_rand.nextInt()).toString();

		Values vals = new Values(tupleId);
		vals.add(sentence);

		if (enableStormsTimeoutMechanism_) {
			// since we want Storm to track the tuples and its acks here
			// we need to give some messageId to emit (3rd argument).
			_collector.emit(outputStream_, vals, tupleId);
		} else {
			_collector.emit(outputStream_, vals);
		}
		
		tupleTracker_.put(tupleId, index);
	}

	@Override
	public void declareOutputFields(OutputFieldsDeclarer declarer) {
		// spout_tupleId is needed to carry forward the rule that
		// tuple[0] should always have tupleId
		// we should probably add a new abstraction for AckingSpouts???
		declarer.declareStream(outputStream_, new Fields("spoutTupleId", "word"));
	}

	@Override
	public void fail(Object msgId) {
		if(tupleTracker_.get(msgId.toString()) != null) {
			System.out.println("ERROR: Tuple with message ID {" + msgId.toString() + "} has failed");
			emitTuple(tupleTracker_.get(msgId.toString()));
			tupleTracker_.remove(msgId.toString());
		}
	}
	
	@Override
	public void ack(Object msgId) {
		tupleTracker_.remove(msgId.toString());
	}
	
}
