/*
 * Copyright: Almende B.V. (2014), Rotterdam, The Netherlands
 * License: The Apache Software License, Version 2.0
 */
package com.almende.eve.agent.google;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.joda.time.DateTime;

import com.almende.eve.agent.Agent;
import com.almende.eve.agent.TaskAgent;
import com.almende.eve.config.Config;
import com.almende.eve.entity.calendar.Authorization;
import com.almende.eve.rpc.annotation.Access;
import com.almende.eve.rpc.annotation.AccessType;
import com.almende.eve.rpc.annotation.Name;
import com.almende.eve.rpc.annotation.Optional;
import com.almende.eve.rpc.jsonrpc.JSONRPCException;
import com.almende.eve.rpc.jsonrpc.JSONRPCException.CODE;
import com.almende.eve.rpc.jsonrpc.jackson.JOM;
import com.almende.eve.state.State;
import com.almende.util.HttpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The Class GoogleTaskAgent.
 */
@Access(AccessType.PUBLIC)
public class GoogleTaskAgent extends Agent implements TaskAgent {
	private static final Logger	LOG				= Logger.getLogger(GoogleTaskAgent.class
														.getName());
	
	private static final String	OAUTH_URI		= "https://accounts.google.com/o/oauth2";
	private static final String	CALENDAR_URI	= "https://www.googleapis.com/tasks/v1/";
	
	/**
	 * Set access token and refresh token, used to authorize the calendar agent.
	 * These tokens must be retrieved via Oauth 2.0 authorization.
	 * 
	 * @param access_token
	 *            the access_token
	 * @param token_type
	 *            the token_type
	 * @param expires_in
	 *            the expires_in
	 * @param refresh_token
	 *            the refresh_token
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 */
	public void setAuthorization(
			@Name("access_token") final String access_token,
			@Name("token_type") final String token_type,
			@Name("expires_in") final Integer expires_in,
			@Name("refresh_token") final String refresh_token)
			throws IOException {
		LOG.info("setAuthorization");
		
		final State state = getState();
		
		// retrieve user information
		final String url = "https://www.googleapis.com/oauth2/v1/userinfo";
		final Map<String, String> headers = new HashMap<String, String>();
		headers.put("Authorization", token_type + " " + access_token);
		final String resp = HttpUtil.get(url, headers);
		
		final ObjectNode info = JOM.getInstance().readValue(resp,
				ObjectNode.class);
		final String email = info.has("email") ? info.get("email").asText()
				: null;
		final String name = info.has("name") ? info.get("name").asText() : null;
		
		final DateTime expires_at = calculateExpiresAt(expires_in);
		final Authorization auth = new Authorization(access_token, token_type,
				expires_at, refresh_token);
		
		// store the tokens in the state
		state.put("auth", auth);
		state.put("email", email);
		state.put("name", name);
	}
	
	/**
	 * Calculate the expiration time from a life time
	 * 
	 * @param expires_in
	 *            Expiration time in seconds
	 * @return
	 */
	private DateTime calculateExpiresAt(final Integer expires_in) {
		DateTime expires_at = null;
		if (expires_in != null && expires_in != 0) {
			// calculate expiration time, and subtract 5 minutes for safety
			expires_at = DateTime.now().plusSeconds(expires_in).minusMinutes(5);
		}
		return expires_at;
	}
	
	/**
	 * Refresh the access token using the refresh token
	 * the tokens in provided authorization object will be updated
	 * 
	 * @param auth
	 * @throws Exception
	 */
	private void refreshAuthorization(final Authorization auth)
			throws Exception {
		final String refresh_token = (auth != null) ? auth.getRefreshToken()
				: null;
		if (refresh_token == null) {
			throw new Exception("No refresh token available");
		}
		
		final Config config = getAgentHost().getConfig();
		final String client_id = config.get("google", "client_id");
		final String client_secret = config.get("google", "client_secret");
		
		// retrieve new access_token using the refresh_token
		final Map<String, String> params = new HashMap<String, String>();
		params.put("client_id", client_id);
		params.put("client_secret", client_secret);
		params.put("refresh_token", refresh_token);
		params.put("grant_type", "refresh_token");
		final String resp = HttpUtil.postForm(OAUTH_URI + "/token", params);
		final ObjectNode json = JOM.getInstance().readValue(resp,
				ObjectNode.class);
		if (!json.has("access_token")) {
			// TODO: give more specific error message
			throw new Exception("Retrieving new access token failed");
		}
		
		// update authorization
		if (json.has("access_token")) {
			auth.setAccessToken(json.get("access_token").asText());
		}
		if (json.has("expires_in")) {
			final Integer expires_in = json.get("expires_in").asInt();
			final DateTime expires_at = calculateExpiresAt(expires_in);
			auth.setExpiresAt(expires_at);
		}
	}
	
	/**
	 * Get ready-made HTTP request headers containing the authorization token
	 * Example usage: HttpUtil.get(url, getAuthorizationHeaders());
	 * 
	 * @return
	 * @throws Exception
	 */
	private Map<String, String> getAuthorizationHeaders() throws Exception {
		final Authorization auth = getAuthorization();
		
		final String access_token = (auth != null) ? auth.getAccessToken()
				: null;
		if (access_token == null) {
			throw new Exception("No authorization token available");
		}
		final String token_type = (auth != null) ? auth.getTokenType() : null;
		if (token_type == null) {
			throw new Exception("No token type available");
		}
		
		final Map<String, String> headers = new HashMap<String, String>();
		headers.put("Authorization", token_type + " " + access_token);
		return headers;
	}
	
	/**
	 * Retrieve authorization tokens
	 * 
	 * @return
	 * @throws Exception
	 */
	private Authorization getAuthorization() throws Exception {
		final Authorization auth = getState().get("auth", Authorization.class);
		
		// check if access_token is expired
		final DateTime expires_at = (auth != null) ? auth.getExpiresAt() : null;
		if (expires_at != null && expires_at.isBeforeNow()) {
			// TODO: remove this logging
			LOG.info("access token is expired. refreshing now...");
			refreshAuthorization(auth);
			getState().put("auth", auth);
		}
		
		return auth;
	}
	
	/**
	 * Get the calendar agents version.
	 * 
	 * @return the description
	 */
	@Override
	public String getDescription() {
		return "This agent gives access to a Google Tasks. "
				+ "It allows to search tasks "
				+ "and add, edit, or remove tasks.";
	}
	
	/**
	 * Get the calendar agents description.
	 * 
	 * @return the version
	 */
	@Override
	public String getVersion() {
		return "0.1";
	}
	
	/**
	 * Get the username associated with the tasks.
	 * 
	 * @return name
	 */
	@Override
	public String getUsername() {
		return getState().get("name", String.class);
	}
	
	/**
	 * Get the email associated with the tasks.
	 * 
	 * @return name
	 */
	@Override
	public String getEmail() {
		return getState().get("email", String.class);
	}
	
	/**
	 * Retrieve a list with all calendars in this google calendar
	 * 
	 * @return String with id of the task list
	 */
	private String getDefaultTaskList() throws Exception {
		
		String defaultList = getState().get("defaultList", String.class);
		if (defaultList == null) {
			final ArrayNode taskLists = getTaskList();
			for (final JsonNode taskList : taskLists) {
				if (taskList.get("title").textValue().equals("Paige Task List")) {
					getState().put("defaultList",
							taskList.get("id").textValue());
					return taskList.get("id").textValue();
				}
			}
		}
		
		// No default list found so going to create one
		if (defaultList == null) {
			ObjectNode taskList = JOM.createObjectNode();
			taskList.put("title", "Paige Task List");
			taskList = createTaskList(taskList);
			defaultList = taskList.get("id").textValue();
			getState().put("defaultList", defaultList);
		}
		
		return defaultList;
	}
	
	/**
	 * Create a task list.
	 * 
	 * @param taskList
	 *            JSON structure containing a taskList
	 * @return JSON sturcture with created tasklist
	 * @throws Exception
	 *             the exception
	 */
	public ObjectNode createTaskList(@Name("taskList") final ObjectNode taskList)
			throws Exception {
		
		final String url = CALENDAR_URI + "users/@me/lists";
		
		// perform POST request
		final ObjectMapper mapper = JOM.getInstance();
		final String body = mapper.writeValueAsString(taskList);
		final Map<String, String> headers = getAuthorizationHeaders();
		headers.put("Content-Type", "application/json");
		final String resp = HttpUtil.post(url, body, headers);
		final ObjectNode createdTaskList = mapper.readValue(resp,
				ObjectNode.class);
		
		// check for errors
		if (createdTaskList.has("error")) {
			final ObjectNode error = (ObjectNode) createdTaskList.get("error");
			throw new JSONRPCException(error);
		}
		
		LOG.info("createTaskList="
				+ JOM.getInstance().writeValueAsString(createdTaskList));
		
		return createdTaskList;
	}
	
	/**
	 * Retrieve a list with all task lists in this google tasks.
	 * 
	 * @return the task list
	 * @throws Exception
	 *             the exception
	 */
	@Override
	public ArrayNode getTaskList() throws Exception {
		final String url = CALENDAR_URI + "users/@me/lists";
		final String resp = HttpUtil.get(url, getAuthorizationHeaders());
		final ObjectNode calendars = JOM.getInstance().readValue(resp,
				ObjectNode.class);
		
		// check for errors
		if (calendars.has("error")) {
			final ObjectNode error = (ObjectNode) calendars.get("error");
			throw new JSONRPCException(error);
		}
		
		// get items from response
		ArrayNode items = null;
		if (calendars.has("items")) {
			items = (ArrayNode) calendars.get("items");
		} else {
			items = JOM.createArrayNode();
		}
		
		return items;
	}
	
	/**
	 * Retrieve a list of task on a certain task list.
	 * 
	 * @param dueMin
	 *            Minimal due time (optional)
	 * @param dueMax
	 *            Maximal due time (optional)
	 * @param taskListId
	 *            the task list id
	 * @return the tasks
	 * @throws Exception
	 *             the exception
	 */
	@Override
	public ArrayNode getTasks(@Optional @Name("dueMin") final String dueMin,
			@Optional @Name("dueMax") final String dueMax,
			@Optional @Name("taskListId") String taskListId) throws Exception {
		if (taskListId == null) {
			taskListId = getDefaultTaskList();
		}
		
		if (taskListId == null) {
			throw new Exception("No tasklist given and no default list found");
		}
		// built url with query parameters
		String url = CALENDAR_URI + "lists/" + taskListId + "/tasks";
		final Map<String, String> params = new HashMap<String, String>();
		if (dueMin != null) {
			params.put("dueMin", new DateTime(dueMin).toString());
		}
		if (dueMax != null) {
			params.put("dueMax", new DateTime(dueMax).toString());
		}
		// Set singleEvents=true to expand recurring events into instances
		// params.put("singleEvents", "true");
		url = HttpUtil.appendQueryParams(url, params);
		
		// perform GET request
		final Map<String, String> headers = getAuthorizationHeaders();
		final String resp = HttpUtil.get(url, headers);
		final ObjectMapper mapper = JOM.getInstance();
		final ObjectNode json = mapper.readValue(resp, ObjectNode.class);
		
		// check for errors
		if (json.has("error")) {
			final ObjectNode error = (ObjectNode) json.get("error");
			throw new JSONRPCException(error);
		}
		
		// get items from the response
		ArrayNode items = null;
		if (json.has("items")) {
			items = (ArrayNode) json.get("items");
			
			/*
			 * TODO: cleanup?
			 * // convert from Google to Eve event
			 * for (int i = 0; i < items.size(); i++) {
			 * ObjectNode item = (ObjectNode) items.get(i);
			 * toEveEvent(item);
			 * }
			 */
		} else {
			items = JOM.createArrayNode();
		}
		
		return items;
	}
	
	/**
	 * Get a single task by id.
	 * 
	 * @param taskId
	 *            Id of the task
	 * @param taskListId
	 *            the task list id
	 * @return the task
	 * @throws Exception
	 *             the exception
	 */
	@Override
	public ObjectNode getTask(@Name("taskId") final String taskId,
			@Optional @Name("taskListId") String taskListId) throws Exception {
		if (taskListId == null) {
			taskListId = getDefaultTaskList();
		}
		
		// built url
		final String url = CALENDAR_URI + "lists/" + taskListId + "/tasks/"
				+ taskId;
		
		// perform GET request
		final Map<String, String> headers = getAuthorizationHeaders();
		final String resp = HttpUtil.get(url, headers);
		final ObjectMapper mapper = JOM.getInstance();
		final ObjectNode task = mapper.readValue(resp, ObjectNode.class);
		
		LOG.info("getTask task="
				+ (task != null ? JOM.getInstance().writeValueAsString(task)
						: null));
		
		// check for errors
		if (task.has("error")) {
			final ObjectNode error = (ObjectNode) task.get("error");
			final Integer code = error.has("code") ? error.get("code").asInt()
					: null;
			if (code != null && code.equals(404)) {
				throw new JSONRPCException(CODE.NOT_FOUND);
			}
			
			throw new JSONRPCException(error);
		}
		
		// check if canceled. If so, return null
		// TODO: be able to retrieve canceled events?
		if (task.has("status")
				&& task.get("status").asText().equals("cancelled")) {
			throw new JSONRPCException(CODE.NOT_FOUND);
		}
		
		return task;
	}
	
	/**
	 * Create a task.
	 * 
	 * @param task
	 *            JSON structure containing the task
	 * @param taskListId
	 *            the task list id
	 * @return createdTask JSON structure with the created task
	 * @throws Exception
	 *             the exception
	 */
	@Override
	public ObjectNode createTask(@Name("task") final ObjectNode task,
			@Optional @Name("taskListId") String taskListId) throws Exception {
		if (taskListId == null) {
			taskListId = getDefaultTaskList();
		}
		
		// built url
		final String url = CALENDAR_URI + "lists/" + taskListId + "/tasks";
		
		// perform POST request
		final ObjectMapper mapper = JOM.getInstance();
		final String body = mapper.writeValueAsString(task);
		final Map<String, String> headers = getAuthorizationHeaders();
		headers.put("Content-Type", "application/json");
		final String resp = HttpUtil.post(url, body, headers);
		final ObjectNode createdTask = mapper.readValue(resp, ObjectNode.class);
		
		// check for errors
		if (createdTask.has("error")) {
			final ObjectNode error = (ObjectNode) createdTask.get("error");
			throw new JSONRPCException(error);
		}
		
		LOG.info("createTask="
				+ JOM.getInstance().writeValueAsString(createdTask));
		
		return createdTask;
	}
	
	/**
	 * Update an existing task.
	 * 
	 * @param task
	 *            JSON structure containing the task
	 *            (task must have an id)
	 * @param taskListId
	 *            the task list id
	 * @return updatedTask JSON structure with the updated task
	 * @throws Exception
	 *             the exception
	 */
	@Override
	public ObjectNode updateTask(@Name("task") final ObjectNode task,
			@Optional @Name("taskListId") String taskListId) throws Exception {
		if (taskListId == null) {
			taskListId = getDefaultTaskList();
		}
		
		// read id from event
		final String id = task.get("id").asText();
		if (id == null) {
			throw new Exception("Parameter 'id' missing in task");
		}
		
		// built url
		final String url = CALENDAR_URI + "lists/" + taskListId + "/tasks/"
				+ id;
		
		// perform POST request
		final ObjectMapper mapper = JOM.getInstance();
		final String body = mapper.writeValueAsString(task);
		final Map<String, String> headers = getAuthorizationHeaders();
		headers.put("Content-Type", "application/json");
		final String resp = HttpUtil.put(url, body, headers);
		final ObjectNode updatedTask = mapper.readValue(resp, ObjectNode.class);
		
		// check for errors
		if (updatedTask.has("error")) {
			final ObjectNode error = (ObjectNode) updatedTask.get("error");
			throw new JSONRPCException(error);
		}
		
		LOG.info("updateTask="
				+ JOM.getInstance().writeValueAsString(updatedTask)); // TODO:
																		// cleanup
		
		return updatedTask;
	}
	
	/**
	 * Delete an existing task.
	 * 
	 * @param taskId
	 *            id of the task to be deleted
	 * @param taskListId
	 *            the task list id
	 * @throws Exception
	 *             the exception
	 */
	@Override
	public void deleteTask(@Name("taskId") final String taskId,
			@Optional @Name("taskListId") String taskListId) throws Exception {
		if (taskListId == null) {
			taskListId = getDefaultTaskList();
		}
		
		LOG.info("deleteTask taskId=" + taskId + ", taskListId=" + taskListId); // TODO:
																				// cleanup
		
		// built url
		final String url = CALENDAR_URI + "lists/" + taskListId + "/tasks/"
				+ taskId;
		
		// perform POST request
		final Map<String, String> headers = getAuthorizationHeaders();
		final String resp = HttpUtil.delete(url, headers);
		if (!resp.isEmpty()) {
			throw new Exception(resp);
		}
	}
	
	/**
	 * Remove all stored data from this agent.
	 */
	public void clear() {
		final State state = getState();
		state.remove("auth");
		state.remove("email");
		state.remove("name");
		state.remove("defaultList");
	}
	
}
