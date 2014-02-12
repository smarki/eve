/*
 * Copyright: Almende B.V. (2014), Rotterdam, The Netherlands
 * License: The Apache Software License, Version 2.0
 */
package com.almende.eve.goldemo;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;

import com.almende.eve.agent.Agent;
import com.almende.eve.agent.annotation.ThreadSafe;
import com.almende.eve.agent.callback.AsyncCallback;
import com.almende.eve.rpc.annotation.Access;
import com.almende.eve.rpc.annotation.AccessType;
import com.almende.eve.rpc.annotation.Name;
import com.almende.eve.rpc.annotation.Sender;
import com.almende.eve.rpc.jsonrpc.JSONRPCException;
import com.almende.eve.rpc.jsonrpc.jackson.JOM;
import com.almende.util.TypeUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The Class Cell.
 */
@Access(AccessType.PUBLIC)
@ThreadSafe(true)
public class Cell extends Agent {
	private ArrayList<String> neighbors = null;
	
	/**
	 * Creates the.
	 * 
	 * @param neighbors
	 *            the neighbors
	 * @param initState
	 *            the init state
	 */
	public void create(@Name("neighbors") ArrayList<String> neighbors,
			@Name("state") Boolean initState) {
		
		getState().put("neighbors", neighbors);
		getState().put("val_0", new CycleState(0, initState));
		getState().put("current_cycle", 1);
		
	}
	
	/**
	 * Register.
	 * 
	 * @throws JSONRPCException
	 *             the jSONRPC exception
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 */
	public void register() throws JSONRPCException, IOException {
		if (neighbors == null){
			neighbors = getState().get("neighbors",
					new TypeUtil<ArrayList<String>>() {
					});
		}
		for (String neighbor : neighbors) {
			getEventsFactory().subscribe(URI.create(neighbor),
					"cycleCalculated", "askCycleState");
		}
	}
	
	/**
	 * Stop.
	 * 
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 * @throws JSONRPCException
	 *             the jSONRPC exception
	 */
	public void stop() throws IOException, JSONRPCException{
		getEventsFactory().clear();
	}
	
	/**
	 * Start.
	 * 
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 */
	public void start() throws IOException {
		getEventsFactory().trigger("cycleCalculated");
	}
	
	/**
	 * Ask cycle state.
	 * 
	 * @param neighbor
	 *            the neighbor
	 * @throws JSONRPCException
	 *             the jSONRPC exception
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 * @throws URISyntaxException
	 *             the uRI syntax exception
	 */
	public void askCycleState(@Sender final String neighbor) throws JSONRPCException,
			IOException, URISyntaxException {
		
		final String neighborId = getAgentHost().getAgentId(URI.create(neighbor));
		ObjectNode params = JOM.createObjectNode();
		params.put("cycle", getState().get("current_cycle", Integer.class) - 1);
		sendAsync(URI.create(neighbor), "getCycleState", params, new AsyncCallback<CycleState>(){

			@Override
			public void onSuccess(CycleState state) {
				if (state != null) {
					getState().put(neighborId + "_" + state.getCycle(), state);
					try {
						calcCycle();
					} catch (URISyntaxException e) {
						e.printStackTrace();
					}
				}
			}

			@Override
			public void onFailure(Exception exception) {
				// TODO Auto-generated method stub
				
			}
			
		},
		CycleState.class);
	}
	
	//TODO: find a way to do this without synchronized
	private synchronized void calcCycle() throws URISyntaxException {
		if (getState().containsKey("current_cycle")) {
			Integer currentCycle = getState().get("current_cycle",
					Integer.class);

			if (neighbors == null){
				neighbors = getState().get("neighbors",
						new TypeUtil<ArrayList<String>>() {
						});
			}

			int aliveNeighbors = 0;
			for (String neighbor : neighbors) {
				final String neighborId = getAgentHost().getAgentId(URI.create(neighbor));
						
				if (!getState()
						.containsKey(neighborId + "_" + (currentCycle - 1))) {
					return;
				}
				CycleState nState = getState().get(
						neighborId + "_" + (currentCycle - 1), CycleState.class);
				if (nState.isAlive()) aliveNeighbors++;
			}
			for (String neighbor : neighbors) {
				final String neighborId = getAgentHost().getAgentId(URI.create(neighbor));
				getState().remove(neighborId + "_" + (currentCycle - 1));
			}
			CycleState myState = getState().get("val_" + (currentCycle - 1),
					CycleState.class);
			if (aliveNeighbors < 2 || aliveNeighbors > 3) {
				getState().put("val_" + currentCycle,
						new CycleState(currentCycle, false));
			} else if (aliveNeighbors == 3) {
				getState().put("val_" + currentCycle,
						new CycleState(currentCycle, true));
			} else {
				getState().put("val_" + currentCycle,
						new CycleState(currentCycle, myState.isAlive()));
			}
			getState().put("current_cycle", currentCycle + 1);
			try {
				getEventsFactory().trigger("cycleCalculated");
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	/**
	 * Gets the cycle state.
	 * 
	 * @param cycle
	 *            the cycle
	 * @return the cycle state
	 */
	public CycleState getCycleState(@Name("cycle") int cycle) {
		if (getState().containsKey("val_" + cycle)) {
			return getState().get("val_" + cycle, CycleState.class);
		}
		return null;
	}
	
	/**
	 * Gets the all cycle states.
	 * 
	 * @return the all cycle states
	 */
	public ArrayList<CycleState> getAllCycleStates() {
		ArrayList<CycleState> result = new ArrayList<CycleState>();
		int count = 0;
		while (getState().containsKey("val_" + count)) {
			result.add(getState().get("val_" + count, CycleState.class));
			count++;
		}
		return result;
	}

}
