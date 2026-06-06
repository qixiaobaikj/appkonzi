/**
 * 远程控制 App 信令服务器
 * 
 * 职责：
 * 1. 管理被控端(Agent)和控制端(Controller)的注册
 * 2. 在控制端和被控端之间转发消息（屏幕帧、触控事件）
 * 3. 维护设备列表，通知控制端设备上下线
 * 
 * 消息协议：
 * 
 * 【注册】
 * Agent -> Server: { type: "register", role: "agent", deviceId: "xxx", deviceName: "奶奶的手机" }
 * Controller -> Server: { type: "register", role: "controller", deviceId: "yyy" }
 * 
 * 【控制端请求设备列表】
 * Controller -> Server: { type: "get_devices" }
 * Server -> Controller: { type: "device_list", devices: [{deviceId, deviceName, online}] }
 * 
 * 【连接/断开】
 * Controller -> Server: { type: "connect", targetDeviceId: "xxx" }
 * Controller -> Server: { type: "disconnect" }
 * Server -> Agent: { type: "controller_connected", controllerId: "yyy" }
 * Server -> Agent: { type: "controller_disconnected" }
 * 
 * 【屏幕帧 Agent->Controller】
 * Agent -> Server: { type: "frame", data: "base64jpeg...", width: 1080, height: 1920 }
 * Server -> Controller: { type: "frame", data: "base64jpeg...", width, height, timestamp }
 * 
 * 【触控事件 Controller->Agent】
 * Controller -> Server: { type: "touch", action: "down|move|up", x: 0.5, y: 0.3 }
 * Controller -> Server: { type: "key", keyCode: "BACK|HOME|RECENT" }
 * Server -> Agent: (原样转发)
 * 
 * 【心跳】
 * 任意方向: { type: "ping" } <-> { type: "pong" }
 */

const WebSocket = require('ws');
const { v4: uuidv4 } = require('uuid');
const os = require('os');

const PORT = process.env.PORT || 8080;

// 存储所有连接的客户端
// key: ws对象, value: { deviceId, role, deviceName, ws, connectedTo }
const clients = new Map();

// 存储设备ID到ws的映射（仅Agent）
// key: deviceId, value: ws
const agents = new Map();

// 存储控制端 -> 被控端 的连接关系
// key: controller ws, value: agent deviceId
const connections = new Map();

const wss = new WebSocket.Server({ port: PORT });

// 获取本机局域网IP，方便打印
function getLocalIP() {
  const interfaces = os.networkInterfaces();
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        return iface.address;
      }
    }
  }
  return 'localhost';
}

// 向指定ws安全发送消息
function send(ws, msg) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(msg));
  }
}

// 广播设备列表给所有在线的控制端
function broadcastDeviceList() {
  const deviceList = [];
  for (const [ws, info] of clients.entries()) {
    if (info.role === 'agent') {
      deviceList.push({
        deviceId: info.deviceId,
        deviceName: info.deviceName || '未知设备',
        online: true,
      });
    }
  }

  for (const [ws, info] of clients.entries()) {
    if (info.role === 'controller') {
      send(ws, { type: 'device_list', devices: deviceList });
    }
  }
}

wss.on('connection', (ws, req) => {
  const remoteAddr = req.socket.remoteAddress;
  console.log(`[${new Date().toLocaleTimeString()}] 新连接来自: ${remoteAddr}`);

  // 初始化客户端信息
  clients.set(ws, {
    deviceId: null,
    role: null,
    deviceName: null,
    ws,
    connectedTo: null,
  });

  ws.on('message', (rawData) => {
    let msg;
    try {
      msg = JSON.parse(rawData.toString());
    } catch (e) {
      console.error('JSON解析失败:', e.message);
      return;
    }

    const clientInfo = clients.get(ws);
    if (!clientInfo) return;

    switch (msg.type) {

      // ==================== 注册 ====================
      case 'register': {
        const { role, deviceId, deviceName } = msg;
        const id = deviceId || uuidv4();

        clientInfo.deviceId = id;
        clientInfo.role = role;
        clientInfo.deviceName = deviceName || (role === 'agent' ? '被控设备' : '控制端');

        if (role === 'agent') {
          agents.set(id, ws);
          console.log(`[注册] Agent: ${clientInfo.deviceName} (${id})`);
        } else {
          console.log(`[注册] Controller: (${id})`);
        }

        send(ws, { type: 'registered', deviceId: id });

        // 注册完后，向控制端发设备列表
        if (role === 'controller') {
          const deviceList = [];
          for (const [agentId, agentWs] of agents.entries()) {
            const agentInfo = clients.get(agentWs);
            if (agentInfo) {
              deviceList.push({
                deviceId: agentId,
                deviceName: agentInfo.deviceName,
                online: true,
              });
            }
          }
          send(ws, { type: 'device_list', devices: deviceList });
        }

        // 有新Agent上线，通知所有控制端
        if (role === 'agent') {
          broadcastDeviceList();
        }
        break;
      }

      // ==================== 获取设备列表 ====================
      case 'get_devices': {
        const deviceList = [];
        for (const [agentId, agentWs] of agents.entries()) {
          const agentInfo = clients.get(agentWs);
          if (agentInfo) {
            deviceList.push({
              deviceId: agentId,
              deviceName: agentInfo.deviceName,
              online: true,
            });
          }
        }
        send(ws, { type: 'device_list', devices: deviceList });
        break;
      }

      // ==================== 控制端请求连接Agent ====================
      case 'connect': {
        const { targetDeviceId } = msg;
        const agentWs = agents.get(targetDeviceId);

        if (!agentWs || agentWs.readyState !== WebSocket.OPEN) {
          send(ws, { type: 'error', message: '设备不在线或不存在' });
          return;
        }

        // 记录连接关系
        clientInfo.connectedTo = targetDeviceId;
        connections.set(ws, targetDeviceId);

        // 通知Agent有控制端连接
        send(agentWs, {
          type: 'controller_connected',
          controllerId: clientInfo.deviceId,
        });

        send(ws, { type: 'agent_connected', deviceId: targetDeviceId });
        console.log(`[连接] Controller(${clientInfo.deviceId}) -> Agent(${targetDeviceId})`);
        break;
      }

      // ==================== 控制端断开连接 ====================
      case 'disconnect': {
        const targetDeviceId = connections.get(ws);
        if (targetDeviceId) {
          const agentWs = agents.get(targetDeviceId);
          send(agentWs, { type: 'controller_disconnected' });
          connections.delete(ws);
          clientInfo.connectedTo = null;
          console.log(`[断开] Controller(${clientInfo.deviceId}) 断开连接`);
        }
        break;
      }

      // ==================== 屏幕帧（Agent -> Controller）====================
      case 'frame': {
        // 找到连接该Agent的所有控制端，转发帧
        const agentId = clientInfo.deviceId;
        for (const [ctrlWs, targetId] of connections.entries()) {
          if (targetId === agentId) {
            send(ctrlWs, {
              type: 'frame',
              data: msg.data,
              width: msg.width,
              height: msg.height,
              timestamp: Date.now(),
            });
          }
        }
        break;
      }

      // ==================== 触控/按键事件（Controller -> Agent）====================
      case 'touch':
      case 'key': {
        const targetDeviceId = connections.get(ws);
        if (targetDeviceId) {
          const agentWs = agents.get(targetDeviceId);
          send(agentWs, msg); // 原样转发
        }
        break;
      }

      // ==================== 心跳 ====================
      case 'ping': {
        send(ws, { type: 'pong' });
        break;
      }

      case 'pong': {
        // 忽略
        break;
      }

      default:
        console.log(`[未知消息类型] ${msg.type}`);
    }
  });

  ws.on('close', () => {
    const clientInfo = clients.get(ws);
    if (!clientInfo) return;

    const { role, deviceId, deviceName } = clientInfo;
    console.log(`[${new Date().toLocaleTimeString()}] 断开: ${role} ${deviceName || ''} (${deviceId})`);

    if (role === 'agent' && deviceId) {
      agents.delete(deviceId);

      // 通知连接到该Agent的控制端
      for (const [ctrlWs, targetId] of connections.entries()) {
        if (targetId === deviceId) {
          send(ctrlWs, { type: 'agent_disconnected', deviceId });
          connections.delete(ctrlWs);
        }
      }

      broadcastDeviceList();
    }

    if (role === 'controller') {
      // 通知Agent控制端已下线
      const targetDeviceId = connections.get(ws);
      if (targetDeviceId) {
        const agentWs = agents.get(targetDeviceId);
        send(agentWs, { type: 'controller_disconnected' });
        connections.delete(ws);
      }
    }

    clients.delete(ws);
  });

  ws.on('error', (err) => {
    console.error('WebSocket错误:', err.message);
  });
});

const localIP = getLocalIP();
console.log('=======================================');
console.log('  远程控制 信令服务器 已启动');
console.log(`  局域网地址: ws://${localIP}:${PORT}`);
console.log(`  本机地址:   ws://127.0.0.1:${PORT}`);
console.log('  将以上地址填入App中即可连接');
console.log('=======================================');
