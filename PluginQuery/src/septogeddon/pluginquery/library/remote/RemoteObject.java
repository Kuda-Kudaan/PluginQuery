package septogeddon.pluginquery.library.remote;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import septogeddon.pluginquery.api.QueryConnection;
import septogeddon.pluginquery.api.QueryListener;
import septogeddon.pluginquery.utils.InstanceBuffer;
import septogeddon.pluginquery.utils.QueryUtil;

/***
 * RemoteObject controller
 * @author Thito Yalasatria Sunarya
 *
 * @param <T> Anything
 */
public class RemoteObject<T> {

	private static Method finalizeMethod;
	static {
		try {
			finalizeMethod = Object.class.getDeclaredMethod("finalize");
		} catch (NoSuchMethodException | SecurityException e) {
			throw new IllegalStateException("cannot find finalize method on Object. Bug?", e);
		}
	}
	protected ExecutorService executorService;
	protected String channel;
	protected long futureTimeout = 1000 * 30;
	protected T object;
	protected QueryConnection connection;
	protected boolean queueQuery = true;
	protected AtomicLong lastQueueId = new AtomicLong();
	protected ClassRegistry classRegistry;
	protected Map<Long, RemoteFuture> queuedInvocation = new ConcurrentHashMap<>();
	protected ReferenceContext context = new ReferenceContext();
	protected RemoteListener listener = new RemoteListener();
	protected boolean closed;
	protected T crossoverObject;
	protected Class<T> clazz;
	/***
	 * Initialize RemoteObject and act as Object Receiver
	 * @param channel the channel
	 * @param connection the connection
	 * @param clazz the expected class
	 * @param registry the class registry
	 */
	public RemoteObject(String channel, QueryConnection connection, Class<T> clazz, ClassRegistry registry) {
		this(channel, connection, null, clazz, registry);
	}
	/***
	 * Initialize RemoteObject and act as Object Sender
	 * @param channel the channel
	 * @param connection the connection
	 * @param object the provided object
	 * @param registry the class registry
	 */
	public RemoteObject(String channel, QueryConnection connection, T object, ClassRegistry registry) {
		this(channel, connection, object, null, registry);
	}
	private RemoteObject(String channel, QueryConnection connection, T object, Class<T> clazz, ClassRegistry registry) {
		QueryUtil.illegalArgument(object != null && clazz != null, "illegal constructor argument");
		this.channel = channel;
		this.classRegistry = registry;
		this.object = object;
		this.connection = connection;
		QueryUtil.illegalState(clazz != null && !clazz.isInterface(), "represented remote object class must be an interface");
		this.clazz = clazz;
		connection.getEventBus().registerListener(listener);
	}
	
	/***
	 * Get the reference context
	 * @return reference context for this remote object
	 */
	public ReferenceContext getContext() {
		return context;
	}
	
	/***
	 * Get the class registry
	 * @return class registry for this remote object
	 */
	public ClassRegistry getClassRegistry() {
		return classRegistry;
	}
	
	/***
	 * Is this object queue query when the connection is inactive?
	 * @return true if queue is enabled
	 */
	public boolean isQueueQuery() {
		return queueQuery;
	}
	
	/***
	 * Set whether the remote object should queue the query when the connection goes inactive
	 * @param queueQuery should queue?
	 */
	public void setQueueQuery(boolean queueQuery) {
		this.queueQuery = queueQuery;
	}
	
	protected T getCrossoverObject() throws TimeoutException {
		ping();
		return crossoverObject;
	}
	
	/***
	 * Check if the object is available on the RemoteObject sender
	 * @return
	 */
	public boolean checkPing() {
		try {
			ping();
			return true;
		} catch (Throwable t) {
			return false;
		}
	}
	
	protected void ping() throws TimeoutException {
		long id = nextQueueId();
		InstanceBuffer buffer = new InstanceBuffer();
		buffer.pushObject(hashCode());
		buffer.pushObject(RemoteContext.COMMAND_PING);
		buffer.pushObject(id);
		RemoteFuture future = new RemoteFuture();
		queuedInvocation.put(id, future);
		try {
			connection.sendQuery(channel, buffer.toByteArray()).addListener(future);
			if (futureTimeout < 0) {
				future.get();
			} else {
				future.get(futureTimeout, TimeUnit.MILLISECONDS);
			}
		} catch (IOException e) {
		} catch (ExecutionException e) {
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}
	
	/***
	 * Get ThreadPooling handler
	 * @return the service, null if its run synchronously inside connection thread
	 */
	public ExecutorService getExecutorService() {
		return executorService;
	}
	
	/***
	 * Set handler for ThreadPooling
	 * @param executorService the service
	 */
	public void setExecutorService(ExecutorService executorService) {
		this.executorService = executorService;
	}
	
	/***
	 * Max timeout for object reflection and connection
	 * @return -1 if unlimited
	 */
	public long getFutureTimeout() {
		return futureTimeout;
	}
	
	/***
	 * Set max timeout for object reflection and connection
	 * @param futureTimeout -1 for unlimited timeout
	 */
	public void setFutureTimeout(long futureTimeout) {
		this.futureTimeout = futureTimeout;
	}
	
	/***
	 * Close the remote object. Disable for future object processing.
	 */
	public void close() {
		closed = true;
		finalizeReferences();
		connection.getEventBus().unregisterListener(listener);
	}
	
	/***
	 * Is the Remote closed?
	 * @return true if its closed
	 */
	public boolean isClosed() {
		return closed;
	}
	
	protected long nextQueueId() {
		return lastQueueId.getAndIncrement();
	}
	
	/***
	 * Get the connection bridge for the remote object
	 * @return connection
	 */
	public QueryConnection getConnection() {
		return connection;
	}
	
	/***
	 * Get the object preserved for this remote connection
	 * @return the object
	 * @throws TimeoutException if it reaches the timeout time
	 */
	public T getObject() throws TimeoutException {
		return object == null ? getCrossoverObject() : object;
	}
	
	protected void finalizeReferences() {
		context.clearReferences();
		queuedInvocation.forEach((a,b)->b.completeExceptionally(new IllegalStateException("remote connection closed")));
		queuedInvocation.clear();
	}
	
	protected void submit(Runnable r) {
		if (executorService == null) r.run(); // synchronous anyway
		else executorService.submit(r); // asynchronous
	}
	
	protected Method findMethod(Object refer, String method, Class<?>[] hint, Object...args) {
		for (Method met : refer.getClass().getMethods()) {
			if (args == null) {
				if (met.getParameterCount() == 0 && met.getName().equals(method)) {
					return met;
				}
			} else {
				if (met.getParameterCount() == args.length) {
					boolean match = true;
					Class<?>[] param = met.getParameterTypes();
					for (int i = 0; i < args.length; i++) {
						Class<?> clazz = param[i];
						Object object = args[i];
						Class<?> givenHint = hint[i];
						if (givenHint != null && !clazz.isAssignableFrom(givenHint)) {
							match = false;
							break;
						}
						if (object != null && !clazz.isInstance(object)) {
							match = false;
							break;
						}
					}
					if (match) {
						return met;
					}
				}
			}
		}
		return null;
	}
	
	protected Object[] unfilter(Class<?>[] hint, Object[]args) {
		if (args != null) {
			for (int i = 0; i < args.length; i++) {
				args[i] = unfilter(hint == null ? null : hint[i], args[i]);
			}
		}
		return args;
	}
	
	protected Object unfilter(Class<?>hint, Object target) {
		if (target instanceof ReferencedObject) {
			if (((ReferencedObject) target).isReceiverSide()) {
				return context.getReferenced(((ReferencedObject) target).getId());
			} else {
				return newObject(((ReferencedObject) target).getId(), hint);
			}
		} else if (target != null && target.getClass().isArray()) {
			QueryUtil.illegalArgument(!hint.isArray(), hint.getName()+" is not an array");
			int length = Array.getLength(target);
			Class<?> type = hint.getComponentType();
			Object newType = Array.newInstance(type, length);
			for (int i = 0; i < length; i++) {
				Object element = Array.get(target, i);
				Array.set(newType, i, unfilter(type, element));
			}
			return newType;
		}
		return target;
	}
 	
	protected Object[] filter(Object[] args) {
		if (args != null) {
			for (int i = 0; i < args.length; i++) {
				args[i] = filter(args[i]);
			}
		}
		return args;
	}
	
	protected Object filter(Object target) {
		if (target != null && target.getClass().isArray()) {
			int length = Array.getLength(target);
			// sterilize array for safe transfer
			Object[] newType = new Object[length];
			for (int i = 0; i < length; i++) {
				newType[i] = filter(Array.get(target, i));
			}
			return newType;
		}
		if (target instanceof ObjectReference) {
			if (((ObjectReference) target).getReferenceHandler().getOwner() == this) {
				return new ReferencedObject(((ObjectReference) target).getReferenceHandler().getId(), true);
			} else {
				return context.createReference(target);
			}
		}
		if (target != null && getClassRegistry().isSerializable(target.getClass())) {
			return context.createReference(target);
		}
		return target;
	}
	
	protected void closeCrossReference(long id) {
		InstanceBuffer buffer = new InstanceBuffer();
		buffer.pushObject(hashCode());
		buffer.pushObject(RemoteContext.COMMAND_CLOSE_REFERENCE);
		buffer.pushObject(id);
		try {
			connection.sendQuery(channel, buffer.toByteArray(), isQueueQuery());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public final int hashCode() {
		return super.hashCode();
	}
	
	protected void collect(Class<?> cl, ArrayList<Class<?>> clazz) {
		if (cl == null) return;
		if (cl.isInterface()) {
			clazz.add(cl);
		}
		Class<?>[] interfaces = cl.getInterfaces();
		if (interfaces != null) {
			for (Class<?> in : interfaces) {
				collect(in, clazz);
			}
		}
	}
	
	@SuppressWarnings("all")
	protected <K> K newObject(long id, Class<K> interf) {
		ArrayList<Class<?>> clazz = new ArrayList<>();
		clazz.add(ObjectReference.class);
		collect(interf, clazz);
		final ReferenceHandler loader = new ReferenceHandler(id, this);
		return (K)
				Proxy.newProxyInstance(
						interf.getClassLoader(), 
						clazz.toArray(new Class[0]), 
				(proxy, method, args)->{
					if (method.equals(finalizeMethod)) {
						closeCrossReference(loader.getId());
						return null;
					}
					if (closed) throw new IllegalStateException("remote object closed");
					if (method.equals(ObjectReference.METHOD_GETREFERENCEHANDLER)) {
						return loader;
					}
					InstanceBuffer buffer = new InstanceBuffer();
					buffer.pushObject(hashCode());
					buffer.pushObject(RemoteContext.COMMAND_INVOKE_METHOD);
					long queueId = nextQueueId();
					RemoteFuture future = new RemoteMethodInvocationFuture(loader, method);
					queuedInvocation.put(queueId, future);
					buffer.pushObject(queueId);
					Long methodId = loader.getCachedMethodLookup().get(method);
					buffer.pushObject(loader.getId());
					buffer.pushObject(methodId);
					buffer.pushObject(method.getName());
					buffer.pushObject(filter(args));
					if (methodId == null) {
						Class<?>[] param = method.getParameterTypes();
						String[] parameters = new String[method.getParameterCount()];
						for (int i = 0; i < parameters.length; i++) {
							if (param[i] != null) {
								parameters[i] = param[i].getName();
							}
						}
						buffer.pushObject(parameters);
					}
					connection.sendQuery(channel, buffer.toByteArray(), isQueueQuery())
						.addListener(future);
					try {
						return futureTimeout < 0 ? future.get() : future.get(futureTimeout, TimeUnit.MILLISECONDS);
					} catch (Throwable t) {
						queuedInvocation.remove(queueId);
						throw t;
					}
				});
	}
	
	class RemoteListener implements QueryListener {

		@Override
		public void onConnectionStateChange(QueryConnection connection) {
			if (!connection.isConnected()) {
				// disconnected
				finalizeReferences();
			}
		}

		@Override
		public void onQueryReceived(QueryConnection connection, String channel, byte[] message) throws ClassNotFoundException, IOException {
			if (RemoteObject.this.channel.equals(channel)) {
				InstanceBuffer buffer = new InstanceBuffer(message);
				int hashCode = buffer.pullObject();
				byte command = buffer.pullObject();
				if (command == RemoteContext.COMMAND_INVOKE_METHOD) {
					long queueId = buffer.pullObject();
					long objectId = buffer.pullObject();
					try {
						ReferencedObject stored = context.getReferenced(objectId);
						if (stored.getId() == objectId) {
							if (stored.getObject() instanceof ObjectReference) {
								ObjectReference reference = (ObjectReference)stored.getObject();
								RemoteObject<?> refer = reference.getReferenceHandler().getOwner();
								InstanceBuffer buff = new InstanceBuffer();
								buff.pushObject(queueId);
								buff.pushObject(reference.getReferenceHandler().getId());
								buffer.copyTo(buff);
								refer.getConnection().sendQuery(channel, message);
								return;
							}
							Long methodId = buffer.pullObject();
							String methodName = buffer.pullObject();
							Object[] arguments = buffer.pullObject();
							Method method;
							if (methodId == null) {
								String[] paramName = buffer.pullObject();
								Class<?>[] parameters = new Class<?>[paramName.length];
								for (int i = 0; i < parameters.length; i++) {
									if (paramName[i] != null) {
										try {
											parameters[i] = getClassRegistry().getClass(paramName[i]);
										} catch (ClassNotFoundException e) {
										}
									}
								}
								method = findMethod(stored.getObject(), methodName, parameters, arguments);
							} else {
								method = stored.getCachedMethodLookup().get(methodId);
							}
							if (method != null) {
								final Method finalMethod = method;
								final ReferencedObject finalStored = stored;
								submit(()->{
									try {
										Object result = finalMethod.invoke(finalStored.getObject(), unfilter(finalMethod.getParameterTypes(),arguments));
										buffer.finalize();
										buffer.pushObject(hashCode);
										buffer.pushObject(RemoteContext.COMMAND_RESPONSE_RESULT);
										buffer.pushObject(queueId);
										if (methodId == null) {
											buffer.pushObject(finalStored.cacheMethod(finalMethod));
										} else {
											buffer.pushObject(methodId);
										}
										buffer.pushObject(filter(result));
										connection.sendQuery(channel, buffer.toByteArray(), isQueueQuery());
									} catch (IllegalAccessException e) {
									} catch (IllegalArgumentException | IOException e) {
										e.printStackTrace();
									} catch (InvocationTargetException e) {
										buffer.finalize();
										buffer.pushObject(hashCode);
										buffer.pushObject(RemoteContext.COMMAND_DELIVERED_EXCEPTION);
										buffer.pushObject(queueId);
										buffer.pushObject(e);
										try {
											connection.sendQuery(channel, buffer.toByteArray(), isQueueQuery());
										} catch (IOException e1) {
											e1.printStackTrace();
										}
									}
								});
							} else throw new NoSuchMethodException(methodName);
							return;
						}
						throw new IllegalArgumentException("no reference");
					} catch (Throwable t) {
						buffer.finalize();
						buffer.pushObject(hashCode);
						buffer.pushObject(RemoteContext.COMMAND_DELIVERED_EXCEPTION);
						buffer.pushObject(queueId);
						buffer.pushObject(t);
						connection.sendQuery(channel, buffer.toByteArray(), isQueueQuery());
					}
					return;
				}
				if (command == RemoteContext.COMMAND_CLOSE_REFERENCE && hashCode == RemoteObject.this.hashCode()) {
					long objectId = buffer.pullObject();
					context.closeReference(objectId);
					return;
				}
				if (command == RemoteContext.COMMAND_RESPONSE_RESULT && hashCode == RemoteObject.this.hashCode()) {
					long queueId = buffer.pullObject();
					long methodId = buffer.pullObject();
					Object object = buffer.pullObject();
					RemoteFuture future = queuedInvocation.remove(queueId);
					if (future == null) {
						throw new IllegalStateException("no such future");
					} else {
						if (future instanceof RemoteMethodInvocationFuture) {
							RemoteMethodInvocationFuture rmif = (RemoteMethodInvocationFuture)future;
							rmif.getHandler().getCachedMethodLookup().put(rmif.getMethod(), methodId);
							future.complete(unfilter(rmif.getMethod().getReturnType(), object));
						} else {
							future.complete(object);
						}
					}
					return;
				}
				if (command == RemoteContext.COMMAND_PING) {
					long queueId = buffer.pullObject();
					buffer.finalize();
					buffer.pushObject(hashCode);
					buffer.pushObject(RemoteContext.COMMAND_PONG);
					buffer.pushObject(queueId);
					buffer.pushObject(context.createReference(object));
					connection.sendQuery(channel, buffer.toByteArray());
					return;
				}
				if (command == RemoteContext.COMMAND_PONG && hashCode == RemoteObject.this.hashCode()) {
					long queueId = buffer.pullObject();
					long objectId = buffer.pullObject();
					crossoverObject = newObject(objectId, clazz);
					RemoteFuture future = queuedInvocation.remove(queueId);
					if (future != null) future.complete(null);
					else throw new IllegalStateException("no such future");
					return;
				}
				if (command == RemoteContext.COMMAND_DELIVERED_EXCEPTION && hashCode == RemoteObject.this.hashCode()) {
					Long queueId = buffer.pullObject();
					Throwable thrown = buffer.pullObject();
					if (queueId == null) {
						thrown.printStackTrace();
					} else {
						RemoteFuture future = queuedInvocation.remove(queueId);
						if (future == null) {
							thrown.printStackTrace();
						} else {
							future.completeExceptionally(thrown);
						}
					}
					return;
				}
			}
		}
	}
	
}