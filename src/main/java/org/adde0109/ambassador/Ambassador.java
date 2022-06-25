package org.adde0109.ambassador;

import com.google.common.io.ByteArrayDataInput;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.ServerLoginPluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.LoginPhaseConnection;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.io.EOFException;
import java.net.InetSocketAddress;
import org.adde0109.ambassador.event.PreSyncEvent;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.*;

@Plugin(id = "ambassador", name = "Ambassador", version = "0.1.0-SNAPSHOT", url = "", description = "", authors = {"adde0109"})
public class Ambassador {

  private final ProxyServer server;
  private final Logger logger;
  private final Path dataDirectory;
  private Optional<RegisteredServer> forgeServer;
  private AmbassadorConfig config;

  private static ForgeHandshakeDataHandler forgeHandshakeDataHandler;

  public Map<RegisteredServer, ForgeServerConnection> forgeServerConnectionMap = new HashMap<RegisteredServer,ForgeServerConnection>();
  public Map<InetSocketAddress,ForgeConnection> incomingForgeConnections = new HashMap<InetSocketAddress,ForgeConnection>();;

  @Inject
  public Ambassador(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
    this.server = server;
    this.logger = logger;
    this.dataDirectory = dataDirectory;
  }

  @Subscribe
  public void onProxyInitialization(ProxyInitializeEvent event) {
    config = AmbassadorConfig.readOrCreateConfig(dataDirectory,server,logger);
    if(config != null) {
      forgeHandshakeDataHandler = new ForgeHandshakeDataHandler(logger,server);
      server.getEventManager().register(this, forgeHandshakeDataHandler);
    }
    else {
      logger.warn("Ambassador will be disabled because of errors");
    }

  }
  @Subscribe
  public void onPreLoginEvent(PreLoginEvent event, Continuation continuation) {
    if (!config.shouldHandle(event.getConnection().getProtocolVersion().getProtocol())) {
      continuation.resume();
      return;
    }
    RegisteredServer defaultServer = config.getServer(event.getConnection().getProtocolVersion().getProtocol());

    this.server.getEventManager().fire(new PreSyncEvent(event.getUsername(),event.getConnection(), defaultServer))
        .thenAccept((e) -> {
          if (e.getResult().getServer().isEmpty()) {
            //Do not sync
            return;
          }
          RegisteredServer newServer = e.getResult().getServer().get();



          //If a connection does not already exist, create one.
          if (!forgeServerConnectionMap.containsKey(newServer)) {
            forgeServerConnectionMap.put(newServer, new ForgeServerConnection(this,logger,newServer));
          }

          ForgeServerConnection forgeServerConnection = forgeServerConnectionMap.get(newServer);

          //Syncing - continuation is forwarded to this method
          ForgeConnection.sync((LoginPhaseConnection) event.getConnection(),forgeServerConnection,continuation).thenAccept(
              this::onSyncComplete);
        });
  }

  public void onSyncComplete(ForgeConnection forgeConnection) {
    if (forgeConnection != null) {
      incomingForgeConnections.values().removeIf((c) -> !c.getConnection().isActive());
      incomingForgeConnections.put(forgeConnection.getConnection().getRemoteAddress(), forgeConnection);
    }
  }


  private ForgeConnection getForgeConnection(InetSocketAddress socketAddress) {
    incomingForgeConnections.values().removeIf((c) -> !c.getConnection().isActive());
    return incomingForgeConnections.get(socketAddress);
  }



  @Subscribe
  public void onServerLoginPluginMessageEvent(ServerLoginPluginMessageEvent event, Continuation continuation) {
    //Only respond the servers that we can respond to
    if(!forgeServerConnectionMap.containsKey(event.getConnection().getServer())) {
      continuation.resume();
      return;
    }
    //Grab the connection responsible for this - no pun intended
    ForgeServerConnection connection = forgeServerConnectionMap.get(event.getConnection().getServer());
    connection.handle(event,continuation);
  }



}
