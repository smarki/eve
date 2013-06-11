package com.almende.eve.agent;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ProtocolException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.joda.time.DateTime;

import com.almende.eve.agent.annotation.ThreadSafe;
import com.almende.eve.agent.log.EventLogger;
import com.almende.eve.agent.proxy.AsyncProxy;
import com.almende.eve.config.Config;
import com.almende.eve.rpc.RequestParams;
import com.almende.eve.rpc.annotation.Sender;
import com.almende.eve.rpc.jsonrpc.JSONRPC;
import com.almende.eve.rpc.jsonrpc.JSONRPCException;
import com.almende.eve.rpc.jsonrpc.JSONRequest;
import com.almende.eve.rpc.jsonrpc.JSONResponse;
import com.almende.eve.scheduler.Scheduler;
import com.almende.eve.scheduler.SchedulerFactory;
import com.almende.eve.state.MemoryStateFactory;
import com.almende.eve.state.State;
import com.almende.eve.state.StateFactory;
import com.almende.eve.transport.AsyncCallback;
import com.almende.eve.transport.TransportService;
import com.almende.eve.transport.http.HttpService;
import com.almende.util.ClassUtil;
import com.almende.util.TypeUtil;

public final class AgentFactory implements AgentFactoryInterface {
	
	// Note: the CopyOnWriteArrayList is inefficient but thread safe.
	private List<TransportService>					transportServices	= new CopyOnWriteArrayList<TransportService>();
	private StateFactory							stateFactory		= null;
	private SchedulerFactory						schedulerFactory	= null;
	private Config									config				= null;
	private EventLogger								eventLogger			= new EventLogger(
																				this);
	private boolean									doesShortcut		= true;
	
	private static final Map<String, AgentFactory>	FACTORIES			= new ConcurrentHashMap<String, AgentFactory>();
	private static final Map<String, String>		STATE_FACTORIES		= new HashMap<String, String>();
	private static final Map<String, String>		SCHEDULERS			= new HashMap<String, String>();
	private static final Map<String, String>		TRANSPORT_SERVICES	= new HashMap<String, String>();
	private static final RequestParams				EVEREQUESTPARAMS	= new RequestParams();
	private static final Logger						LOG					= Logger.getLogger(AgentFactory.class
																				.getSimpleName());
	static {
		STATE_FACTORIES.put("FileStateFactory",
				"com.almende.eve.state.FileStateFactory");
		STATE_FACTORIES.put("MemoryStateFactory",
				"com.almende.eve.state.MemoryStateFactory");
		STATE_FACTORIES.put("DatastoreStateFactory",
				"com.almende.eve.state.google.DatastoreStateFactory");
	}
	static {
		SCHEDULERS.put("RunnableSchedulerFactory",
				"com.almende.eve.scheduler.RunnableSchedulerFactory");
		SCHEDULERS.put("ClockSchedulerFactory",
				"com.almende.eve.scheduler.ClockSchedulerFactory");
		SCHEDULERS.put("GaeSchedulerFactory",
				"com.almende.eve.scheduler.google.GaeSchedulerFactory");
	}
	static {
		TRANSPORT_SERVICES.put("XmppService",
				"com.almende.eve.transport.xmpp.XmppService");
		TRANSPORT_SERVICES.put("HttpService",
				"com.almende.eve.transport.http.HttpService");
	}
	static {
		EVEREQUESTPARAMS.put(Sender.class, null);
	}
	
	private AgentFactory() {
	}
	
	/**
	 * Get a shared AgentFactory instance with the default namespace "default"
	 * 
	 * @return factory Returns the factory instance, or null when not existing
	 */
	public static AgentFactory getInstance() {
		return getInstance(null);
	}
	
	/**
	 * Get a shared AgentFactory instance with a specific namespace
	 * 
	 * @param namespace
	 *            If null, "default" namespace will be loaded.
	 * @return factory Returns the factory instance, or null when not existing
	 */
	public static AgentFactory getInstance(String namespace) {
		if (namespace == null) {
			namespace = "default";
		}
		
		return FACTORIES.get(namespace);
	}
	
	/**
	 * Create a shared AgentFactory instance with the default namespace
	 * "default"
	 * 
	 * @return factory
	 */
	public static synchronized AgentFactory createInstance() {
		return createInstance(null, null);
	}
	
	/**
	 * Create a shared AgentFactory instance with the default namespace
	 * "default"
	 * 
	 * @param config
	 * @return factory
	 */
	public static synchronized AgentFactory createInstance(Config config) {
		return createInstance(null, config);
	}
	
	/**
	 * Create a shared AgentFactory instance with a specific namespace
	 * 
	 * @param namespace
	 * @return factory
	 */
	public static synchronized AgentFactory createInstance(String namespace) {
		return createInstance(namespace, null);
	}
	/**
	 * Use the given AgentFactory as the new shared AgentFactory instance.
	 * @param factory
	 */
	public static synchronized void registerInstance(AgentFactory factory) {
		registerInstance(null, factory);
	}
	
	/**
	 * Use the given AgentFactory as the new shared AgentFactory instance with a specific namespace.
	 * @param factory
	 */
	public static synchronized void registerInstance(String namespace,
			AgentFactory factory) {
		if (namespace == null) {
			namespace = "default";
		}
		FACTORIES.put(namespace, factory);
	}
	
	/**
	 * Create a shared AgentFactory instance with a specific namespace
	 * 
	 * @param namespace
	 *            If null, "default" namespace will be loaded.
	 * @param config
	 *            If null, a non-configured AgentFactory will be created.
	 * @return factory
	 */
	public static synchronized AgentFactory createInstance(String namespace,
			Config config) {
		if (namespace == null) {
			namespace = "default";
		}
		
		if (FACTORIES.containsKey(namespace)) {
			throw new IllegalStateException(
					"Shared AgentFactory with namespace '"
							+ namespace
							+ "' already exists. "
							+ "A shared AgentFactory can only be created once. "
							+ "Use getInstance instead to get the existing shared instance.");
		}
		
		AgentFactory factory = new AgentFactory();
		factory.setConfig(config);
		if (config != null) {
			AgentCache.configCache(config);
			
			// initialize all factories for state, transport, and scheduler
			// important to initialize in the correct order: cache first,
			// then the state and transport services, and lastly scheduler.
			factory.setStateFactory(config);
			factory.addTransportServices(config);
			// ensure there is always an HttpService for outgoing calls
			factory.addTransportService(new HttpService());
			factory.setSchedulerFactory(config);
			factory.addAgents(config);
		} else {
			// ensure there is at least a memory state service
			factory.setStateFactory(new MemoryStateFactory());
			// ensure there is always an HttpService for outgoing calls
			factory.addTransportService(new HttpService());
		}
		FACTORIES.put(namespace, factory);
		factory.boot();
		return factory;
	}
	
	/**
	 * Get string describing this environment (e.g. Production or Development)
	 * @return
	 */
	public static String getEnvironment() {
		//TODO: make this non-static?
		return Config.getEnvironment();
	}
	
	/**
	 * Set the current environment
	 * @param env
	 */
	public static void setEnvironment(String env) {
		//TODO: make this non-static?
		Config.setEnvironment(env);
	}
	
	@Override
	public void boot() {
		if (stateFactory != null) {
			Iterator<String> iter = stateFactory.getAllAgentIds();
			if (iter != null) {
				while (iter.hasNext()) {
					try {
						Agent agent = getAgent(iter.next());
						if (agent != null) {
							agent.boot();
						}
					} catch (Exception e) {
					}
				}
			}
		}
	}
	
	@Override
	public Agent getAgent(String agentId) throws JSONRPCException,
			ClassNotFoundException, InstantiationException,
			IllegalAccessException, InvocationTargetException,
			NoSuchMethodException {
		if (agentId == null) {
			return null;
		}
		
		// Check if agent is instantiated already, returning if it is:
		Agent agent = AgentCache.get(agentId);
		if (agent != null) {
			return agent;
		}
		// No agent found, normal initialization:
		
		// load the State
		State state = null;
		state = getStateFactory().get(agentId);
		if (state == null) {
			// agent does not exist
			return null;
		}
		state.init();
		
		// read the agents class name from state
		Class<?> agentType = state.getAgentType();
		if (agentType == null) {
			throw new JSONRPCException("Cannot instantiate agent. "
					+ "Class information missing in the agents state "
					+ "(agentId='" + agentId + "')");
		}
		
		// instantiate the agent
		agent = (Agent) agentType.getConstructor().newInstance();
		agent.constr(this, state);
		agent.init();
		
		if (agentType.isAnnotationPresent(ThreadSafe.class)
				&& agentType.getAnnotation(ThreadSafe.class).value()) {
			AgentCache.put(agentId, agent);
		}
		
		return agent;
	}
	
	@Deprecated
	@Override
	public <T> T createAgentProxy(final URI receiverUrl, Class<T> agentInterface) {
		return createAgentProxy(null, receiverUrl, agentInterface);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <T> T createAgentProxy(final AgentInterface sender,
			final URI receiverUrl, Class<T> agentInterface) {
		if (!ClassUtil.hasInterface(agentInterface, AgentInterface.class)) {
			throw new IllegalArgumentException("agentInterface must extend "
					+ AgentInterface.class.getName());
		}
		
		// http://docs.oracle.com/javase/1.4.2/docs/guide/reflection/proxy.html
		T proxy = (T) Proxy.newProxyInstance(agentInterface.getClassLoader(),
				new Class[] { agentInterface }, new InvocationHandler() {
					public Object invoke(Object proxy, Method method,
							Object[] args) throws ProtocolException,
							JSONRPCException {
						JSONRequest request = JSONRPC.createRequest(method,
								args);
						JSONResponse response = send(sender, receiverUrl,
								request);
						
						JSONRPCException err = response.getError();
						if (err != null) {
							throw err;
						} else if (response.getResult() != null
								&& !method.getReturnType().equals(Void.TYPE)) {
							return TypeUtil.inject(
									method.getGenericReturnType(),
									response.getResult());
						} else {
							return null;
						}
					}
				});
		
		// TODO: for optimization, one can cache the created proxy's
		
		return proxy;
	}
	
	@Deprecated
	@Override
	public <T> AsyncProxy<T> createAsyncAgentProxy(final URI receiverUrl,
			Class<T> agentInterface) {
		return createAsyncAgentProxy(null, receiverUrl, agentInterface);
	}
	
	@Override
	public <T> AsyncProxy<T> createAsyncAgentProxy(final AgentInterface sender,
			final URI receiverUrl, Class<T> agentInterface) {
		return new AsyncProxy<T>(createAgentProxy(sender, receiverUrl,
				agentInterface));
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <T extends Agent> T createAgent(String agentType, String agentId)
			throws JSONRPCException, InstantiationException,
			IllegalAccessException, InvocationTargetException,
			NoSuchMethodException, ClassNotFoundException, IOException {
		return (T) createAgent((Class<T>) Class.forName(agentType), agentId);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <T extends Agent> T createAgent(Class<T> agentType, String agentId)
			throws JSONRPCException, InstantiationException,
			IllegalAccessException, InvocationTargetException,
			NoSuchMethodException, IOException {
		if (!ClassUtil.hasSuperClass(agentType, Agent.class)) {
			return (T) createAspectAgent(agentType, agentId);
		}
		
		// validate the Eve agent and output as warnings
		List<String> errors = JSONRPC.validate(agentType, EVEREQUESTPARAMS);
		for (String error : errors) {
			LOG.warning("Validation error class: " + agentType.getName()
					+ ", message: " + error);
		}
		
		// create the state
		State state = getStateFactory().create(agentId);
		state.setAgentType(agentType);
		state.init();
		
		// instantiate the agent
		T agent = (T) agentType.getConstructor().newInstance();
		agent.constr(this, state);
		agent.create();
		agent.init();
		
		if (agentType.isAnnotationPresent(ThreadSafe.class)
				&& agentType.getAnnotation(ThreadSafe.class).value()) {
			AgentCache.put(agentId, agent);
		}
		
		return agent;
	}
	
	@Override
	public <T> AspectAgent<T> createAspectAgent(Class<? extends T> aspect,
			String agentId) throws JSONRPCException, InstantiationException,
			IllegalAccessException, InvocationTargetException,
			NoSuchMethodException, IOException {
		@SuppressWarnings("unchecked")
		AspectAgent<T> result = createAgent(AspectAgent.class, agentId);
		result.init(aspect);
		return result;
	}
	
	@Override
	public void deleteAgent(String agentId) throws JSONRPCException,
			ClassNotFoundException, InstantiationException,
			IllegalAccessException, InvocationTargetException,
			NoSuchMethodException {
		if (agentId == null) {
			return;
		}
		Agent agent = getAgent(agentId);
		if (agent == null) {
			return;
		}
		
		if (getScheduler(agent) != null) {
			schedulerFactory.destroyScheduler(agentId);
		}
		try {
			// get the agent and execute the delete method
			agent.destroy();
			agent.delete();
			AgentCache.delete(agentId);
			agent = null;
		} catch (Exception e) {
			LOG.log(Level.WARNING, "Error deleting agent:" + agentId, e);
		}
		
		// delete the state, even if the agent.destroy or agent.delete
		// failed.
		getStateFactory().delete(agentId);
	}
	
	@Override
	public boolean hasAgent(String agentId) throws JSONRPCException {
		return getStateFactory().exists(agentId);
	}
	
	@Override
	public EventLogger getEventLogger() {
		return eventLogger;
	}
	
	@Override
	public JSONResponse receive(String receiverId, JSONRequest request,
			RequestParams requestParams) throws JSONRPCException {
		try {
			Agent receiver = getAgent(receiverId);
			if (receiver != null) {
				JSONResponse response = JSONRPC.invoke(receiver, request,
						requestParams, receiver);
				receiver.destroy();
				return response;
			}
		} catch (Exception e) {
			throw new JSONRPCException("Couldn't instantiate agent for id '"
					+ receiverId + "'", e);
		}
		throw new JSONRPCException("Agent with id '" + receiverId
				+ "' not found");
	}
	
	@Deprecated
	@Override
	public JSONResponse send(URI receiverUrl, JSONRequest request)
			throws ProtocolException, JSONRPCException {
		return send(null, receiverUrl, request);
	}
	
	@Override
	public JSONResponse send(AgentInterface sender, URI receiverUrl,
			JSONRequest request) throws ProtocolException, JSONRPCException {
		String receiverId = getAgentId(receiverUrl.toASCIIString());
		String senderUrl = null;
		if (sender != null) {
			senderUrl = getSenderUrl(sender.getId(),
					receiverUrl.toASCIIString());
		}
		if (doesShortcut && receiverId != null) {
			// local shortcut
			RequestParams requestParams = new RequestParams();
			requestParams.put(Sender.class, senderUrl);
			return receive(receiverId, request, requestParams);
		} else {
			TransportService service = null;
			String protocol = receiverUrl.getScheme();
			service = getTransportService(protocol);
			
			if (service != null) {
				JSONResponse response = service.send(senderUrl,
						receiverUrl.toASCIIString(), request);
				return response;
			} else {
				throw new ProtocolException(
						"No transport service configured for protocol '"
								+ protocol + "'.");
			}
		}
	}
	
	@Deprecated
	@Override
	public void sendAsync(final URI receiverUrl, final JSONRequest request,
			final AsyncCallback<JSONResponse> callback)
			throws ProtocolException, JSONRPCException {
		sendAsync(null, receiverUrl, request, callback);
	}
	
	@Override
	public void sendAsync(final AgentInterface sender, final URI receiverUrl,
			final JSONRequest request,
			final AsyncCallback<JSONResponse> callback)
			throws JSONRPCException, ProtocolException {
		final String receiverId = getAgentId(receiverUrl.toASCIIString());
		if (doesShortcut && receiverId != null) {
			// local shortcut
			new Thread(new Runnable() {
				@Override
				public void run() {
					JSONResponse response;
					try {
						String senderUrl = null;
						if (sender != null) {
							senderUrl = getSenderUrl(sender.getId(),
									receiverUrl.toASCIIString());
						}
						RequestParams requestParams = new RequestParams();
						requestParams.put(Sender.class, senderUrl);
						response = receive(receiverId, request, requestParams);
						callback.onSuccess(response);
					} catch (Exception e) {
						callback.onFailure(e);
					}
				}
			}).start();
		} else {
			TransportService service = null;
			String protocol = null;
			String senderUrl = null;
			if (sender != null) {
				senderUrl = getSenderUrl(sender.getId(),
						receiverUrl.toASCIIString());
			}
			protocol = receiverUrl.getScheme();
			service = getTransportService(protocol);
			if (service != null) {
				service.sendAsync(senderUrl, receiverUrl.toASCIIString(),
						request, callback);
			} else {
				throw new ProtocolException(
						"No transport service configured for protocol '"
								+ protocol + "'.");
			}
		}
	}
	
	@Override
	public String getAgentId(String agentUrl) {
		if (agentUrl.startsWith("local:")) {
			return agentUrl.replaceFirst("local:/?/?", "");
		}
		for (TransportService service : transportServices) {
			String agentId = service.getAgentId(agentUrl);
			if (agentId != null) {
				return agentId;
			}
		}
		return null;
	}
	
	@Override
	public String getSenderUrl(String agentId, String receiverUrl) {
		if (receiverUrl.startsWith("local:")) {
			return "local://" + agentId;
		}
		for (TransportService service : transportServices) {
			List<String> protocols = service.getProtocols();
			for (String protocol : protocols) {
				if (receiverUrl.startsWith(protocol + ":")) {
					String senderUrl = service.getAgentUrl(agentId);
					if (senderUrl != null) {
						return senderUrl;
					}
				}
			}
		}
		return null;
	}
	
	
	@Override
	public void setConfig(Config config) {
		this.config = config;
	}
	
	@Override
	public Config getConfig() {
		return config;
	}
	
	@Override
	public boolean isDoesShortcut() {
		return doesShortcut;
	}
	
	@Override
	public void setDoesShortcut(boolean doesShortcut) {
		this.doesShortcut = doesShortcut;
	}
	
	@Override
	public void setStateFactory(Config config) {
		// get the class name from the config file
		// first read from the environment specific configuration,
		// if not found read from the global configuration
		String className = config.get("state", "class");
		String configName = "state";
		if (className == null) {
			className = config.get("context", "class");
			if (className == null) {
				throw new IllegalArgumentException(
						"Config parameter 'state.class' missing in Eve configuration.");
			} else {
				LOG.warning("Use of config parameter 'context' is deprecated, please use 'state' instead.");
				configName = "context";
			}
		}
		
		// TODO: deprecated since "2013-02-20"
		if ("FileContextFactory".equals(className)) {
			LOG.warning("Use of Classname FileContextFactory is deprecated, please use 'FileStateFactory' instead.");
			className = "FileStateFactory";
		}
		if ("MemoryContextFactory".equals(className)) {
			LOG.warning("Use of Classname MemoryContextFactory is deprecated, please use 'MemoryStateFactory' instead.");
			className = "MemoryStateFactory";
		}
		if ("DatastoreContextFactory".equals(className)) {
			LOG.warning("Use of Classname DatastoreContextFactory is deprecated, please use 'DatastoreStateFactory' instead.");
			className = "DatastoreStateFactory";
		}
		
		// Recognize known classes by their short name,
		// and replace the short name for the full class path
		for (String name : STATE_FACTORIES.keySet()) {
			if (className.equalsIgnoreCase(name)) {
				className = STATE_FACTORIES.get(name);
				break;
			}
		}
		
		try {
			// get the class
			Class<?> stateClass = Class.forName(className);
			if (!ClassUtil.hasInterface(stateClass, StateFactory.class)) {
				throw new IllegalArgumentException("State factory class "
						+ stateClass.getName() + " must extend "
						+ State.class.getName());
			}
			
			// instantiate the state factory
			Map<String, Object> params = config.get(configName);
			StateFactory sf = (StateFactory) stateClass.getConstructor(
					Map.class).newInstance(params);
			
			setStateFactory(sf);
			LOG.info("Initialized state factory: " + sf.toString());
		} catch (Exception e) {
			LOG.log(Level.WARNING, "", e);
		}
	}
	
	@Override
	public void addAgents(Config config) {
		Map<String, String> agents = config.get("bootstrap", "agents");
		if (agents != null) {
			for (Entry<String, String> entry : agents.entrySet()) {
				String agentId = entry.getKey();
				String agentType = entry.getValue();
				try {
					Agent agent = getAgent(agentId);
					if (agent == null) {
						// agent does not yet exist. create it
						agent = createAgent(agentType, agentId);
						agent.destroy();
						LOG.info("Bootstrap created agent id=" + agentId
								+ ", type=" + agentType);
					}
				} catch (Exception e) {
					LOG.log(Level.WARNING, "", e);
				}
			}
		}
	}
	
	@Override
	public void setStateFactory(StateFactory stateFactory) {
		this.stateFactory = stateFactory;
	}
	
	@Override
	public StateFactory getStateFactory() throws JSONRPCException {
		if (stateFactory == null) {
			throw new JSONRPCException("No state factory initialized.");
		}
		return stateFactory;
	}
	
	@Override
	public void setSchedulerFactory(Config config) {
		// get the class name from the config file
		// first read from the environment specific configuration,
		// if not found read from the global configuration
		String className = config.get("scheduler", "class");
		if (className == null) {
			throw new IllegalArgumentException(
					"Config parameter 'scheduler.class' missing in Eve configuration.");
		}
		
		// TODO: remove warning some day (added 2013-01-22)
		if (className.equalsIgnoreCase("RunnableScheduler")) {
			LOG.warning("Deprecated class RunnableScheduler configured. Use RunnableSchedulerFactory instead to configure a scheduler factory.");
			className = "RunnableSchedulerFactory";
		}
		if (className.equalsIgnoreCase("AppEngineScheduler")) {
			LOG.warning("Deprecated class AppEngineScheduler configured. Use GaeSchedulerFactory instead to configure a scheduler factory.");
			className = "GaeSchedulerFactory";
		}
		if (className.equalsIgnoreCase("AppEngineSchedulerFactory")) {
			LOG.warning("Deprecated class AppEngineSchedulerFactory configured. Use GaeSchedulerFactory instead to configure a scheduler factory.");
			className = "GaeSchedulerFactory";
		}
		
		// Recognize known classes by their short name,
		// and replace the short name for the full class path
		for (String name : SCHEDULERS.keySet()) {
			if (className.equalsIgnoreCase(name)) {
				className = SCHEDULERS.get(name);
				break;
			}
		}
		
		// read all scheduler params (will be fed to the scheduler factory
		// on construction)
		Map<String, Object> params = config.get("scheduler");
		
		try {
			// get the class
			Class<?> schedulerClass = Class.forName(className);
			if (!ClassUtil.hasInterface(schedulerClass, SchedulerFactory.class)) {
				throw new IllegalArgumentException("Scheduler class "
						+ schedulerClass.getName() + " must implement "
						+ SchedulerFactory.class.getName());
			}
			
			// initialize the scheduler factory
			SchedulerFactory sf = (SchedulerFactory) schedulerClass
					.getConstructor(AgentFactory.class, Map.class).newInstance(
							this, params);
			
			setSchedulerFactory(sf);
			
			LOG.info("Initialized scheduler factory: "
					+ sf.getClass().getName());
		} catch (Exception e) {
			LOG.log(Level.WARNING, "", e);
		}
	}
	
	@Override
	public void addTransportServices(Config config) {
		if (config == null) {
			Exception e = new Exception("Configuration uninitialized");
			LOG.log(Level.WARNING, "", e);
			return;
		}
		
		// read global service params
		List<Map<String, Object>> allTransportParams = config
				.get("transport_services");
		if (allTransportParams == null) {
			// TODO: cleanup some day. deprecated since 2013-01-17
			allTransportParams = config.get("services");
			if (allTransportParams != null) {
				LOG.warning("Property 'services' is deprecated. Use 'transport_services' instead.");
			}
		}
		
		if (allTransportParams != null) {
			int index = 0;
			for (Map<String, Object> transportParams : allTransportParams) {
				String className = (String) transportParams.get("class");
				try {
					if (className != null) {
						// Recognize known classes by their short name,
						// and replace the short name for the full class path
						
						// TODO: remove deprecation warning some day (added
						// 2013-01-24)
						if (className.equalsIgnoreCase("XmppTransportService")) {
							LOG.warning("Deprecated class XmppTransportService, use XmppService instead.");
							className = "XmppService";
						}
						if (className.equalsIgnoreCase("HttpTransportService")) {
							LOG.warning("Deprecated class HttpTransportService, use HttpService instead.");
							className = "HttpService";
						}
						
						for (String name : TRANSPORT_SERVICES.keySet()) {
							if (className.equalsIgnoreCase(name)) {
								className = TRANSPORT_SERVICES.get(name);
								break;
							}
						}
						
						// get class
						Class<?> transportClass = Class.forName(className);
						if (!ClassUtil.hasInterface(transportClass,
								TransportService.class)) {
							throw new IllegalArgumentException(
									"TransportService class "
											+ transportClass.getName()
											+ " must implement "
											+ TransportService.class.getName());
						}
						
						// initialize the transport service
						TransportService transport = (TransportService) transportClass
								.getConstructor(AgentFactory.class, Map.class)
								.newInstance(this, transportParams);
						
						// register the service with the agent factory
						addTransportService(transport);
					} else {
						LOG.warning("Cannot load transport service at index "
								+ index + ": no class defined.");
					}
				} catch (Exception e) {
					LOG.warning("Cannot load service at index " + index + ": "
							+ e.getMessage());
				}
				index++;
			}
		}
	}
	
	@Override
	public void addTransportService(TransportService transportService) {
		transportServices.add(transportService);
		LOG.info("Registered transport service: " + transportService.toString());
	}
	
	@Override
	public void removeTransportService(TransportService transportService) {
		transportServices.remove(transportService);
		LOG.info("Unregistered transport service "
				+ transportService.toString());
	}
	
	@Override
	public List<TransportService> getTransportServices() {
		return transportServices;
	}
	
	@Override
	public List<TransportService> getTransportServices(String protocol) {
		List<TransportService> filteredServices = new ArrayList<TransportService>();
		
		for (TransportService service : transportServices) {
			List<String> protocols = service.getProtocols();
			if (protocols.contains(protocol)) {
				filteredServices.add(service);
			}
		}
		
		return filteredServices;
	}
	
	@Override
	public TransportService getTransportService(String protocol) {
		List<TransportService> services = getTransportServices(protocol);
		if (services.size() > 0) {
			return services.get(0);
		}
		return null;
	}
	
	@Override
	public List<Object> getMethods(Agent agent) {
		Boolean asString = false;
		return JSONRPC.describe(agent, EVEREQUESTPARAMS, asString);
	}
	
	@Override
	public synchronized void setSchedulerFactory(
			SchedulerFactory schedulerFactory) {
		this.schedulerFactory = schedulerFactory;
		this.notifyAll();
	}
	
	@Override
	public synchronized Scheduler getScheduler(Agent agent) {
		DateTime start = DateTime.now();
		while (schedulerFactory == null && start.plus(30000).isBeforeNow()) {
			try {
				this.wait();
			} catch (InterruptedException e) {
				LOG.log(Level.WARNING, "", e);
			}
		}
		if (schedulerFactory == null) {
			LOG.severe("SchedulerFactory is null, while agent " + agent.getId()
					+ " calls for getScheduler");
			return null;
		}
		return schedulerFactory.getScheduler(agent);
	}
	
}
