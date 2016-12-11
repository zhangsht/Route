import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import Utils.HostChannel;
import Utils.Logger;
import Utils.MsgPacket;
import Utils.RouteTable;

public class Host {
	public UI ui;

	public ServerSocket serverSocket = null;
	public List<HostChannel> connList = new ArrayList<>();
	public RouteTable routeTable;
	private static Lock lock = new ReentrantLock(true);
	public String localIP;

	public static final int LISTENING_PORT = 4000;

	public Host() throws IOException {
		routeTable = new RouteTable();
		try {
			serverSocket = new ServerSocket(LISTENING_PORT);
			// 获得本机IP
			BufferedReader br = new BufferedReader(new InputStreamReader(
					System.in));
			System.out.println("please input your IP address");
			localIP = br.readLine();
			ui = new UI(this);
			// 监听邻居节点的连接请求
			while (true) {
				Socket socket = serverSocket.accept();
				Logger.i("main",
						"about connecting to " + socket.getInetAddress());
				new ConnRequestHandler(socket);
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			serverSocket.close();
		}
	}

	/**
	 * 广播路由表 该方法同一时刻只会被一个线程调用
	 * 
	 * @param address
	 *            `IP:port`
	 */
	public synchronized void broadcast(String address) {
		synchronized (this) {
			for (HostChannel hc : connList) {
				try {
					RouteTable rt = routeTable.deepClone();
					hc.sendRouteTable(rt);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

	/**
	 * 广播路由表 该方法同一时刻只会被一个线程调用
	 * 
	 * @param address
	 *            `IP:port`
	 */
	public synchronized void sendMessagePacket(MsgPacket msgPacket) {
		synchronized (this) {
			if (msgPacket.getDesIP().equals(localIP)) {
				ui.outputLabel.setText(msgPacket.getMessage());
				return;
			}
			String nextIp = routeTable.getNextRouteAddress(msgPacket, localIP, connList);
			for (HostChannel hc : connList) {
				try {
					String temString = hc.getIP();
					if (temString.charAt(0) == '/') {
						temString = temString.substring(1);
					}
					if (nextIp.equals(temString)) {
						hc.sendMessage(msgPacket);
						break;
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

	/**
	 * 处理邻居节点的连接请求
	 */
	private class ConnRequestHandler extends Thread {
		private HostChannel neighbor;

		public ConnRequestHandler(Socket socket) {
			try {
				neighbor = new HostChannel(socket);
			} catch (Exception e) {
				e.printStackTrace();
				return;
			}
			Logger.i("requestHandler",
					"setup connection with " + neighbor.getAddress());
			connList.add(neighbor);
			start();
		}

		@Override
		public void run() {
			try {
				MsgPacket msgPacket = null;
				while (true) {
					msgPacket = (MsgPacket) neighbor.getOis().readObject();
					if (msgPacket != null) {
						if (msgPacket.getType() == 0) { // 收到的是路由表
							Logger.logRouteTable(msgPacket.getRouteTable(),
									neighbor.getIP());
							// 如果路由表有改动，广播新路由表
							lock.lock();
							boolean isChanged = routeTable
									.updateTable(msgPacket.getRouteTable());
							lock.unlock();
							if (isChanged) {
								broadcast(neighbor.getIP());
							}
							// Logger.logRouteTable(routeTable);
						} else { // 收到的是信息包
							sendMessagePacket(msgPacket);
							Logger.logMsgPacket(msgPacket, neighbor.getIP());
						}
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				try {
					neighbor.close();
					connList.remove(neighbor);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

	/**
	 * 向邻居请求连接
	 */
	private class ConnRequest extends Thread {
		private HostChannel neighbor;

		public ConnRequest(String IP, int distance) {
			try {
				Socket socket = new Socket(IP, LISTENING_PORT);
				lock.lock();
				routeTable.updateTable(new RouteTable(localIP, IP, distance));
				lock.unlock();
				neighbor = new HostChannel(socket);
			} catch (Exception e) {
				e.printStackTrace();
				return;
			}
			Logger.i("connRequest",
					"setup connection with " + neighbor.getAddress());
			connList.add(neighbor);
			start();
		}

		@Override
		public void run() {
			try {
				// 向邻居发送初始化的链路信息
				broadcast(neighbor.getIP());

				MsgPacket msgPacket = null;
				while (true) {
					msgPacket = (MsgPacket) neighbor.getOis().readObject();
					if (msgPacket != null) {
						if (msgPacket.getType() == 0) { // 收到的是路由表
							Logger.logRouteTable(msgPacket.getRouteTable(),
									neighbor.getIP());
							// 如果路由表有改动，广播新路由表
							lock.lock();
							boolean isChanged = routeTable
									.updateTable(msgPacket.getRouteTable());
							lock.unlock();
							if (isChanged) {
								broadcast(neighbor.getIP());
							}
							// Logger.logRouteTable(routeTable);
						} else { // 收到的是信息包
							sendMessagePacket(msgPacket);
							Logger.logMsgPacket(msgPacket, neighbor.getIP());
						}
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				try {
					neighbor.close();
					connList.remove(neighbor);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

	public void creartConnet(String IP, int distance) {
		new ConnRequest(IP, distance);
	}

	public static void main(String[] args) throws IOException {
		System.out.println("Host up");
		new Host();
	}

}
