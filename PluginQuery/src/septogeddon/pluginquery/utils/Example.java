package septogeddon.pluginquery.utils;

import net.md_5.bungee.BungeeCord;
import net.md_5.bungee.api.config.ServerInfo;
import septogeddon.pluginquery.PluginQuery;
import septogeddon.pluginquery.api.QueryConnection;
import septogeddon.pluginquery.api.QueryConnectionStateListener;
import septogeddon.pluginquery.api.QueryListener;
import septogeddon.pluginquery.api.QueryMessageListener;
import septogeddon.pluginquery.api.QueryMessenger;
import septogeddon.pluginquery.bungeecord.BungeePluginQuery;
import septogeddon.pluginquery.bungeecord.event.QueryMessageEvent;

@SuppressWarnings("all")
public class Example {

	/*
	 * Send plugin message to all active connections.
	 * (Spigot to BungeeCord / BungeeCord to Spigot)
	 */
	public void sendPluginMessage(String channel, byte[] message) {
		QueryMessenger messenger = PluginQuery.getMessenger();
		/*
		 * A spigot server can be connected with multiple connection,
		 * it can be a bungeecord server or another standalone program
		 */
		if (!messenger.broadcastQuery(channel, message)) {
			// it will return false if there is no active connections
			throw new IllegalStateException("no active connections");
		}
	}
	
	/*
	 * Send plugin message to a server
	 * (BungeeCord to Server)
	 */
	public void sendPluginMessage(ServerInfo info, String channel, byte[] message) {
		/*
		 * If this returns null, then the Spigot server is not connected to the BungeeCord
		 * Invalid key perhaps?
		 */
		QueryConnection connection = BungeePluginQuery.getConnection(info);
		if (connection != null) {
			connection.sendQuery(channel, message);
		} else {
			throw new IllegalArgumentException("server is not yet connected");
		}
	}
	
	/*
	 * Register listeners to listen to Query Events
	 */
	public void registerListener() {
		QueryMessenger messenger = PluginQuery.getMessenger();
		messenger.getEventBus().registerListener(new ExampleListener());
		messenger.getEventBus().registerListener(new ExampleListener2());
		messenger.getEventBus().registerListener(new ExampleListener3());
		
		net.md_5.bungee.api.plugin.Plugin plugin = null; // YOUR BUNGEECORD PLUGIN INSTANCE
		BungeeCord.getInstance().getPluginManager().registerListener(plugin, new ExampleListener4());
	}
	
	/*
	 * Listen only to Message event
	 */
	public class ExampleListener implements QueryMessageListener {

		@Override
		public void onQueryReceived(QueryConnection connection, String channel, byte[] message) {
			// QUERY RECEIVED
		}
		
	}
	
	/*
	 * Listen only to Connection State event
	 */
	public class ExampleListener2 implements QueryConnectionStateListener {

		@Override
		public void onConnectionStateChange(QueryConnection connection) {
			if (connection.isConnected()) {
				// CONNECTED!
			} else {
				// DISCONNECTED!
			}
		}
		
	}
	
	/*
	 * Listen to Message and Connection State event
	 */
	public class ExampleListener3 implements QueryListener {

		@Override
		public void onConnectionStateChange(QueryConnection connection) {
			if (connection.isConnected()) {
				// CONNECTED!
			} else {
				// DISCONNECTED!
			}
		}

		@Override
		public void onQueryReceived(QueryConnection connection, String channel, byte[] message) {
			// QUERY RECEIVED
		}
		
	}
	
	/*
	 * Listen Query Message using BungeeCord event API
	 */
	public class ExampleListener4 implements net.md_5.bungee.api.plugin.Listener {
		
		@net.md_5.bungee.event.EventHandler
		public void event(septogeddon.pluginquery.bungeecord.event.QueryMessageEvent event) {
			// the channel
			String channel = event.getTag();
			// the message
			byte[] message = event.getMessage();
			// the connection
			QueryConnection connection = event.getSender();
			// same thing
			connection = event.getReceiver();
		}
		
	}
	
	public class ExampleListener5 implements org.bukkit.event.Listener {
		
		@org.bukkit.event.EventHandler
		public void event(septogeddon.pluginquery.spigot.event.QueryMessageEvent event) {
			// the channel
			String channel = event.getChannel();
			// the message
			byte[] message = event.getMessage();
			// the connection
			QueryConnection connection = event.getConnection();
		}
		
	}
	
}
