#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Influx3xtend - 统一通用 MQTT 格式边缘设备模拟器
通用规范:
{
  "ts": 1766325199634000000,     # 首条数据时间戳（纳秒）
  "interval": 100000,            # 间隔（纳秒），10kHz = 100,000ns。单点时为 0
  "fields": ["vibration", "torque"],
  "values": [
    [0.12, 305.1],
    [0.15, 305.3]
  ]
}
"""

import time
import json
import random
import math
import sys

# 尝试导入 paho-mqtt
try:
    import paho.mqtt.client as mqtt
    HAS_MQTT = True
except ImportError:
    HAS_MQTT = False
    print("[WARN] 'paho-mqtt' library not installed. Running in Dry-Run mode only.")

# MQTT Broker Settings
BROKER_URL = "localhost"
BROKER_PORT = 1883

def setup_client(client_id, name):
    if not HAS_MQTT:
        return None
    try:
        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=client_id, userdata={"name": name})
        client.username_pw_set("edge", "edge")
        client.connect(BROKER_URL, BROKER_PORT, 60)
        client.loop_start()
        return client
    except Exception:
        print(f"[{name}] Could not connect to MQTT Broker at {BROKER_URL}:{BROKER_PORT}. Running in Dry-Run mode.")
        return None

def generate_unified_10khz_card_a(points=10000):
    """生成采集卡 A 10kHz 报文 (振动 & 扭矩)"""
    ts_ns = int(time.time() * 1000000000)
    interval_ns = 100000 # 10kHz = 100us = 100,000ns
    fields = ["vibration", "torque"]
    values = []
    
    for i in range(points):
        t = i / 10000.0
        vibe = 0.15 * math.sin(2 * math.pi * 50.0 * t) + random.gauss(0, 0.02)
        torque = 305.0 + 5.0 * math.cos(2 * math.pi * 10.0 * t) + random.gauss(0, 0.2)
        values.append([round(vibe, 4), round(torque, 2)])
        
    return {
        "ts": ts_ns,
        "interval": interval_ns,
        "fields": fields,
        "values": values
    }

def generate_unified_10khz_card_b(points=10000):
    """生成采集卡 B 10kHz 报文 (三相电流 U, V, W)"""
    ts_ns = int(time.time() * 1000000000)
    interval_ns = 100000 # 10kHz = 100us = 100,000ns
    fields = ["current_u", "current_v", "current_w"]
    values = []
    
    for i in range(points):
        t = i / 10000.0
        u = 100.0 * math.sin(2 * math.pi * 50.0 * t) + random.gauss(0, 0.5)
        v = 100.0 * math.sin(2 * math.pi * 50.0 * t - 2 * math.pi / 3) + random.gauss(0, 0.5)
        w = 100.0 * math.sin(2 * math.pi * 50.0 * t + 2 * math.pi / 3) + random.gauss(0, 0.5)
        values.append([round(u, 2), round(v, 2), round(w, 2)])
        
    return {
        "ts": ts_ns,
        "interval": interval_ns,
        "fields": fields,
        "values": values
    }

def generate_unified_plc_single():
    """生成常规 PLC 单点报文"""
    ts_ns = int(time.time() * 1000000000)
    fields = ["MOT_RUN", "SPIN_DIR", "ACC_1", "PLC_TEMP"]
    values = [
        [1.0, 0.0, round(12.0 + random.uniform(-0.5, 0.5), 2), round(45.0 + random.uniform(-1.0, 1.0), 1)]
    ]
    return {
        "ts": ts_ns,
        "interval": 0,
        "fields": fields,
        "values": values
    }

def main():
    duration_sec = 5
    if len(sys.argv) > 1:
        duration_sec = int(sys.argv[1])
        
    print(f"=== Starting Unified MQTT Telemetry Simulator (Duration: {duration_sec}s) ===")
    
    client_a = setup_client("sim_card_a", "DAQ_CARD_A")
    client_b = setup_client("sim_card_b", "DAQ_CARD_B")
    client_plc = setup_client("sim_plc", "PLC_01")
    
    start_time = time.time()
    count = 0
    
    while time.time() - start_time < duration_sec:
        payload_a = generate_unified_10khz_card_a(points=10000)
        payload_b = generate_unified_10khz_card_b(points=10000)
        payload_plc = generate_unified_plc_single()
        
        json_a = json.dumps(payload_a)
        json_b = json.dumps(payload_b)
        json_plc = json.dumps(payload_plc)
        
        count += 1
        print(f"[{count}s] Card A (10k pts, {len(json_a)} bytes) | Card B (10k pts, {len(json_b)} bytes) | PLC Single ({len(json_plc)} bytes)")
        
        if client_a:
            client_a.publish("telemetry/daq/card_a", json_a)
        if client_b:
            client_b.publish("telemetry/daq/card_b", json_b)
        if client_plc:
            client_plc.publish("telemetry/plc", json_plc)
            
        time.sleep(1.0)
        
    print(f"=== Unified MQTT Simulator Finished. Published {count} seconds of data ===")

if __name__ == "__main__":
    main()
