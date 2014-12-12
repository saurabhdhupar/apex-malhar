package com.datatorrent.demos.udpecho;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by Pramod Immaneni <pramod@datatorrent.com> on 12/11/14.
 */
public class NetworkManager implements Runnable
{

  private static final Logger logger = LoggerFactory.getLogger(NetworkManager.class);

  public static enum ConnectionType { TCP, UDP };

  private static NetworkManager _instance;
  private Selector selector;

  private volatile boolean doRun = false;
  private Thread selThread;
  private long selTimeout = 1000;
  private volatile Exception selEx;

  private Map<ConnectionInfo, ChannelConfiguration> channels;
  private Map<SelectableChannel, ChannelConfiguration> channelConfigurations;

  public static NetworkManager getInstance() throws IOException
  {
    if (_instance == null) {
      synchronized (NetworkManager.class) {
        if (_instance == null) {
          _instance = new NetworkManager();
        }
      }
    }
    return _instance;
  }

  private NetworkManager() throws IOException
  {
    channels = new HashMap<ConnectionInfo, ChannelConfiguration>();
    channelConfigurations = new HashMap<SelectableChannel, ChannelConfiguration>();
  }

  public synchronized <SOCKET> ChannelAction<SOCKET> registerAction(int port, ConnectionType type, ChannelListener<SOCKET> listener, int ops) throws IOException
  {
    boolean startProc = (channels.size() == 0);
    SelectableChannel channel = null;
    SocketAddress address = new InetSocketAddress(port);
    ConnectionInfo connectionInfo = new ConnectionInfo();
    connectionInfo.address = address;
    connectionInfo.connectionType = type;
    ChannelConfiguration channelConfiguration = channels.get(connectionInfo);
    if (channelConfiguration == null) {
      Object osocket = null;
      if (type == ConnectionType.TCP) {
        Socket socket = new Socket();
        socket.bind(address);
        channel = socket.getChannel();
        osocket = socket;
      } else if (type == ConnectionType.UDP) {
        DatagramSocket socket = new DatagramSocket();
        socket.bind(address);
        channel = socket.getChannel();
        osocket = socket;
      }
      if (channel == null) {
        throw new IOException("Unsupported connection type");
      }
      channelConfiguration = new ChannelConfiguration();
      channelConfiguration.actions = new ConcurrentLinkedQueue<ChannelAction>();
      channelConfiguration.channel = channel;
      channelConfiguration.socket = osocket;
      channelConfiguration.connectionInfo = connectionInfo;
      channels.put(connectionInfo, channelConfiguration);
      channelConfigurations.put(channel, channelConfiguration);
    }
    ChannelAction channelAction = new ChannelAction();
    channelAction.listener = listener;
    channelAction.ops = ops;
    channelConfiguration.actions.add(channelAction);
    if (startProc) {
      startProcess();
    }
    channel.register(selector, ops);
    return channelAction;
  }

  public synchronized void unregisterAction(ChannelAction action) throws IOException, InterruptedException
  {
    ChannelConfiguration channelConfiguration = action.channelConfiguration;
    SelectableChannel channel = channelConfiguration.channel;
    if (channelConfiguration != null) {
      channelConfiguration.actions.remove(action);
      if (channelConfiguration.actions.size() == 0) {
        ConnectionInfo connectionInfo = channelConfiguration.connectionInfo;
        channelConfigurations.remove(channel);
        channels.remove(connectionInfo);
        channel.close();
      }
    }
    if (channels.size() == 0) {
      stopProcess();
    }
  }

  private void startProcess() throws IOException
  {
    selector = Selector.open();
    doRun = true;
    selThread = new Thread(this);
    selThread.start();
  }

  private void stopProcess() throws InterruptedException, IOException
  {
    doRun = false;
    selThread.join();
    selector.close();
  }

  @Override
  public void run()
  {
    try {
      while (doRun) {
        int keys = selector.select(selTimeout);
        if (keys > 0) {
          Set<SelectionKey> selectionKeys = selector.selectedKeys();
          for (SelectionKey selectionKey : selectionKeys) {
            int readyOps = selectionKey.readyOps();
            ChannelConfiguration channelConfiguration = channelConfigurations.get(selectionKey.channel());
            Collection<ChannelAction> actions = channelConfiguration.actions;
            for (ChannelAction action : actions) {
              if ((readyOps & action.ops) != 0) {
                action.listener.ready(action, readyOps);
              }
            }
          }
        }
      }
    } catch (IOException e) {
      logger.error("Error in select", e);
      selEx = e;
    }
  }

  public static interface ChannelListener<SOCKET> {
    public void ready(ChannelAction<SOCKET> action, int readyOps);
  }

  public static class ChannelConfiguration<SOCKET> {
    public SelectableChannel channel;
    public SOCKET socket;
    public ConnectionInfo connectionInfo;
    public Collection<ChannelAction> actions;
  }

  public static class ChannelAction<SOCKET> {
    public ChannelConfiguration<SOCKET> channelConfiguration;
    public ChannelListener<SOCKET> listener;
    public int ops;
  }

  private static class ConnectionInfo {
    public SocketAddress address;
    public ConnectionType connectionType;

    @Override
    public boolean equals(Object o)
    {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      ConnectionInfo that = (ConnectionInfo) o;

      if (connectionType != that.connectionType) return false;
      if (!address.equals(that.address)) return false;

      return true;
    }

    @Override
    public int hashCode()
    {
      int result = address.hashCode();
      result = 31 * result + connectionType.hashCode();
      return result;
    }
  }

}