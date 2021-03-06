/**
 * @file ChatAgent.java
 * 
 * @brief
 *        A peer to peer chat agent.
 *        Usage:
 * 
 *        Set username:
 *        HTTP POST http://localhost:8080/EveCore/agents/chatagent/1
 *        {
 *        "id": 1,
 *        "method": "setUsername",
 *        "params":{"username":"Agent1"}
 *        }
 * 
 *        Connect two agents:
 *        HTTP POST http://localhost:8080/EveCore/agents/chatagent/1
 *        {
 *        "id": 1,
 *        "method": "connect",
 *        "params": {
 *        "url": "http://localhost:8080/EveCore/agents/chatagent/2"
 *        }
 *        }
 * 
 *        Post a message:
 *        HTTP POST http://localhost:8080/EveCore/agents/chatagent/1
 *        {
 *        "id": 1,
 *        "method": "post",
 *        "params": {
 *        "message": "hello world"
 *        }
 *        }
 * 
 *        Disconnect an agent:
 *        HTTP POST http://localhost:8080/EveCore/agents/chatagent/1
 *        {
 *        "id": 1,
 *        "method": "disconnect",
 *        "params": {}
 *        }
 * 
 * 
 * @license
 *          Licensed under the Apache License, Version 2.0 (the "License"); you
 *          may not
 *          use this file except in compliance with the License. You may obtain
 *          a copy
 *          of the License at
 * 
 *          http://www.apache.org/licenses/LICENSE-2.0
 * 
 *          Unless required by applicable law or agreed to in writing, software
 *          distributed under the License is distributed on an "AS IS" BASIS,
 *          WITHOUT
 *          WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 *          the
 *          License for the specific language governing permissions and
 *          limitations under
 *          the License.
 * 
 *          Copyright © 2011-2012 Almende B.V.
 * 
 * @author Jos de Jong, <jos@almende.org>
 * @date 2011-04-02
 */

package com.almende.eve.agent.example;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import com.almende.eve.agent.Agent;
import com.almende.eve.rpc.annotation.Access;
import com.almende.eve.rpc.annotation.AccessType;
import com.almende.eve.rpc.annotation.Name;
import com.almende.eve.rpc.jsonrpc.JSONRPCException;
import com.almende.eve.rpc.jsonrpc.jackson.JOM;
import com.almende.util.TypeUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The Class ChatAgent.
 */
@Access(AccessType.PUBLIC)
public class ChatAgent extends Agent {
	
	/**
	 * Get the username.
	 *
	 * @return the username
	 */
	public String getUsername() {
		final String username = getState().get("username", String.class);
		return (username != null) ? username : getMyUrl();
	}
	
	/**
	 * Gets the my url.
	 *
	 * @return the my url
	 */
	private String getMyUrl() {
		final List<String> urls = getUrls();
		return urls.size() > 0 ? urls.get(0) : null;
	}
	
	/**
	 * Set the username, used for displaying messages.
	 *
	 * @param username the new username
	 */
	public void setUsername(@Name("username") final String username) {
		getState().put("username", username);
	}
	
	/**
	 * Post a message to all registered agents (including itself).
	 *
	 * @param message the message
	 * @throws IOException Signals that an I/O exception has occurred.
	 * @throws JSONRPCException the jSONRPC exception
	 */
	public void post(@Name("message") final String message) throws IOException,
			JSONRPCException {
		final List<String> connections = getConnections();
		
		// trigger a "post message"
		final ObjectNode params = JOM.createObjectNode();
		params.put("url", getMyUrl());
		params.put("username", getUsername());
		params.put("message", message);
		getEventsFactory().trigger("post", params);
		
		log(getUsername() + " posts message '" + message + "'" + " to "
				+ connections.size() + " agent(s)");
		
		for (int i = 0; i < connections.size(); i++) {
			final String connection = connections.get(i);
			send(URI.create(connection), "receive", params);
		}
	}
	
	/**
	 * Receive a message from an agent.
	 *
	 * @param url the url
	 * @param username the username
	 * @param message the message
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public void receive(@Name("url") final String url,
			@Name("username") final String username,
			@Name("message") final String message) throws IOException {
		// trigger a "receive" message
		final ObjectNode params = JOM.createObjectNode();
		params.put("url", url);
		params.put("username", username);
		params.put("message", message);
		getEventsFactory().trigger("receive", params);
		
		log(getUsername() + " received message from " + username + ": "
				+ message);
	}
	
	/**
	 * connect two agents with each other.
	 *
	 * @param url Url of an ChatAgent
	 * @throws JSONRPCException the jSONRPC exception
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public void connect(@Name("url") final String url) throws JSONRPCException,
			IOException {
		boolean otherAlreadyConnected = false;
		final ArrayList<String> newConnections = new ArrayList<String>();
		final ArrayList<String> otherConnections = send(URI.create(url),
				"getConnections",
				JOM.getTypeFactory().constructArrayType(String.class));
		
		// get my own connections from the state
		final String urlSelf = getMyUrl();
		ArrayList<String> connections = getState().get("connections",
				new TypeUtil<ArrayList<String>>() {
				});
		if (connections == null) {
			connections = new ArrayList<String>();
		}
		
		for (int i = 0; i < otherConnections.size(); i++) {
			final String connection = otherConnections.get(i);
			if (!connection.equals(urlSelf)) {
				// this agent is not me
				if (connections.indexOf(connection) == -1) {
					// this is an agent that I didn't knew before
					connections.add(connection);
					newConnections.add(connection);
					
					// trigger a "connected" event
					final ObjectNode params = JOM.createObjectNode();
					params.put("url", connection);
					getEventsFactory().trigger("connected", params);
					
					log(getUsername() + " connected to " + connection);
				}
			} else {
				// this agent is me. So, the other agent already knows me
				// (-> thus I don't have to connect to him again)
				otherAlreadyConnected = true;
			}
		}
		
		// add the agent that triggered the connect
		if (connections.indexOf(url) == -1) {
			connections.add(url);
			
			// trigger a "connected" event
			final ObjectNode params = JOM.createObjectNode();
			params.put("url", url);
			getEventsFactory().trigger("connected", params);
			
			log(getUsername() + " connected to " + url);
		}
		if (!otherAlreadyConnected) {
			// the other agent doesn't know me
			newConnections.add(url);
		}
		
		// store the connection list
		getState().put("connections", connections);
		
		// schedule tasks to connect to all newly connected agents
		for (final String connection : newConnections) {
			final ObjectNode params = JOM.createObjectNode();
			params.put("url", urlSelf);
			send(URI.create(connection), "connect", params);
		}
	}
	
	/**
	 * Disconnect this agent from all other agents in the chat room.
	 *
	 * @throws JSONRPCException the jSONRPC exception
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public void disconnect() throws JSONRPCException, IOException {
		final ArrayList<String> connections = getState().get("connections",
				new TypeUtil<ArrayList<String>>() {
				});
		if (connections != null) {
			getState().remove("connections");
			
			log(getUsername() + " disconnecting " + connections.size()
					+ " agent(s)");
			
			for (int i = 0; i < connections.size(); i++) {
				final String url = connections.get(i);
				final String urlSelf = getMyUrl();
				final String method = "removeConnection";
				final ObjectNode params = JOM.createObjectNode();
				params.put("url", urlSelf);
				send(URI.create(url), method, params);
				
				// trigger a "disconnected" event
				final ObjectNode triggerParams = JOM.createObjectNode();
				triggerParams.put("url", url);
				getEventsFactory().trigger("disconnected", triggerParams);
			}
		}
	}
	
	/**
	 * Remove an agent from connections list.
	 *
	 * @param url Url of a connected ChatAgent
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public void removeConnection(@Name("url") final String url)
			throws IOException {
		final ArrayList<String> connections = getState().get("connections",
				new TypeUtil<ArrayList<String>>() {
				});
		if (connections != null) {
			connections.remove(url);
			getState().put("connections", connections);
			
			log(getUsername() + " disconnected from " + url);
			// trigger a "connected" event
			final ObjectNode params = JOM.createObjectNode();
			params.put("url", url);
			getEventsFactory().trigger("disconnected", params);
		}
	}
	
	/**
	 * Retrieve the urls of all agents that are connected.
	 *
	 * @return the connections
	 */
	public List<String> getConnections() {
		final ArrayList<String> connections = getState().get("connections",
				new TypeUtil<ArrayList<String>>() {
				});
		if (connections != null) {
			return connections;
		} else {
			return new ArrayList<String>();
		}
	}
	
	/**
	 * Log a message.
	 *
	 * @param message the message
	 */
	private void log(final String message) {
		final Logger logger = Logger.getLogger(this.getClass().getName());
		logger.info(message);
	}
	
	/* (non-Javadoc)
	 * @see com.almende.eve.agent.Agent#getDescription()
	 */
	@Override
	public String getDescription() {
		return "A peer to peer chat agent. "
				+ "First call setUsername to set the agents usernames (optional). "
				+ "Then use connect to connect an agent to another agent. "
				+ "They will automatically synchronize their adress lists. "
				+ "Then, use post to post a message.";
	}
	
	/* (non-Javadoc)
	 * @see com.almende.eve.agent.Agent#getVersion()
	 */
	@Override
	public String getVersion() {
		return "1.0";
	}
}