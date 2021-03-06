package backtype.storm.task;

public class TimeoutIdentifier {

	private static String DELIMITER = "_";
	
	private String sourceId_;
	private String targetId_;
	private String streamId_;
	
	//	Identifier is of the form sourceId+"_"+targetId+"_"+streamId;
	public TimeoutIdentifier(String identifier) throws UnrecognizedTimeoutIdentifier {
		String[] tokens = identifier.split("[*"+DELIMITER+"*]+");
		if(tokens.length != 3) {
			throw new UnrecognizedTimeoutIdentifier(identifier);
		}
				
		setSourceId(tokens[0]);
		setTargetId(tokens[1]);
		setStreamId(tokens[2]);
	}
	
	public TimeoutIdentifier(String sourceId, String targetId, String streamId) {
		setSourceId(sourceId);
		setTargetId(targetId);
		setStreamId(streamId);
	}

	public String getStreamId() {
		return streamId_;
	}

	public void setStreamId(String streamId_) {
		this.streamId_ = streamId_;
	}

	public String getSourceId() {
		return sourceId_;
	}

	public void setSourceId(String sourceId_) {
		this.sourceId_ = sourceId_;
	}

	public String getTargetId() {
		return targetId_;
	}

	public void setTargetId(String targetId_) {
		this.targetId_ = targetId_;
	}
	
	@Override
	public boolean equals(Object obj) {
		if(obj == null) {
			return false;
		}
		if(obj instanceof TimeoutIdentifier) {
			return (this.sourceId_.equals(((TimeoutIdentifier)obj).sourceId_) &&
					this.targetId_.equals(((TimeoutIdentifier)obj).targetId_) &&
					this.streamId_.equals(((TimeoutIdentifier)obj).streamId_));
		}
		return false;
	}
	
	@Override
	public int hashCode() {
		return sourceId_.hashCode() ^ targetId_.hashCode() ^ streamId_.hashCode();
	}
	
}
