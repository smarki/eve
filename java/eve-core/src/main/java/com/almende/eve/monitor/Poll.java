package com.almende.eve.monitor;

import java.util.logging.Logger;

import com.almende.eve.agent.Agent;
import com.almende.eve.rpc.jsonrpc.JSONRequest;
import com.almende.eve.rpc.jsonrpc.jackson.JOM;
import com.almende.util.uuid.UUID;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class Poll implements ResultMonitorConfigType {
	private static final long	serialVersionUID	= 1521097261949700084L;
	private static final Logger	LOG		= Logger.getLogger(Push.class
			.getCanonicalName());

	private String				id					= null;
	private int					interval;
	private String				taskId				= null;
	
	public Poll(int interval) {
		this.id = new UUID().toString();
		this.interval = interval;
	};
	
	public Poll() {
		this.id = new UUID().toString();
	}
	
	public Poll onInterval(int interval) {
		this.interval = interval;
		return this;
	}
	
	public void stop(Agent agent) {
		if (taskId != null && agent.getScheduler() != null) {
			agent.getScheduler().cancelTask(taskId);
		}
	}
	
	public void start(ResultMonitor monitor, Agent agent) {
		//Try to cancel any protential existing tasks.		
		stop(agent);
		
		ObjectNode params = JOM.createObjectNode();
		params.put("monitorId", monitor.getId());
		params.put("pollId", id);
		JSONRequest request = new JSONRequest("monitor.doPoll", params);
		
		taskId = agent.getScheduler()
				.createTask(request, interval, false, false);
		
		LOG.info("Poll task created:"+monitor.getUrl());
		monitor.getPolls().add(this);
	}
	
	public void init(ResultMonitor monitor, Agent agent) {
				
		try {
			agent.getResultMonitorFactory().doPoll(monitor.getId(), id);
		} catch (Exception e) {
			LOG.warning("Failed to do first poll");
		}
		
		start(monitor, agent);
	}
	
	public String getId() {
		return id;
	}
	
	public void setId(String id) {
		this.id = id;
	}

	public int getInterval() {
		return interval;
	}

	public void setInterval(int interval) {
		this.interval = interval;
	}

	public String getTaskId() {
		return taskId;
	}

	public void setTaskId(String taskId) {
		this.taskId = taskId;
	}
}