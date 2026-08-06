#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
MQTT 工业设备数据模拟发送脚本 (mqtt_publisher.py)
自动模拟两类边缘采集设备定时向 MQTT Broker 发布符合 Influx3xtend 规范的 JSON 报文：
1. 低频单点遥测设备 (DEV_REG_001): 每秒发送1次，含温湿度及电机运行状态 (interval = 0)
2. 10kHz 高频示波器设备 (DEV_HF_002): 每秒发送1包，单包含 10,000 个采样点的振动/扭矩波形 (interval = 100000 ns)
"""

import argparse
import math
import random
import time
import json

try:
    import paho.mqtt.client as mqtt
except ImportError:
    print("错误: 缺少 paho-mqtt 依赖，请在终端执行: pip install paho-mqtt")
    exit(1)


def generate_regular_payload(now_ns: int) -> dict:
    """生成低频单点遥测报文 (interval = 0)"""
    temp = round(25.0 + random.uniform(-1.5, 2.5), 2)
    humidity = round(60.0 + random.uniform(-5.0, 5.0), 2)
    motor_state = 1.0 if random.random() > 0.05 else 0.0
    return {
        "ts": now_ns,
        "interval": 0,
        "fields": ["temperature", "humidity", "motor_state"],
        "values": [
            [temp, humidity, motor_state]
        ]
    }


def generate_high_frequency_payload(now_ns: int, point_count: int = 10000) -> dict:
    """生成 10kHz 高频波形块报文 (interval = 100000 ns = 100 us, 1秒 10000 点)"""
    values = []
    # 模拟正弦波震荡 + 高斯白噪声
    for i in range(point_count):
        t_sec = i / 10000.0
        vibe = round(0.5 * math.sin(2 * math.pi * 50 * t_sec) + random.gauss(0, 0.05), 4)
        torque = round(300.0 + 10.0 * math.cos(2 * math.pi * 10 * t_sec) + random.gauss(0, 0.5), 2)
        values.append([vibe, torque])
    
    return {
        "ts": now_ns,
        "interval": 100000,  # 100,000 ns = 100 us (10kHz)
        "fields": ["vibration", "torque"],
        "values": values
    }


def main():
    parser = argparse.ArgumentParser(description="MQTT 工业遥测与高频波形模拟发送器")
    parser.add_argument("--broker", type=str, default="localhost", help="MQTT Broker 地址 (默认: localhost)")
    parser.add_argument("--port", type=int, default=1883, help="MQTT Broker 端口 (默认: 1883)")
    parser.add_argument("--count", type=int, default=0, help="发送包数量 limit (0 表示持续循环发送)")
    parser.add_argument("--interval-sec", type=float, default=1.0, help="发送周期秒数 (默认: 1.0秒)")
    parser.add_argument("--username", type=str, default="influx3xtend", help="MQTT 用户名 (默认: influx3xtend)")
    parser.add_argument("--password", type=str, default="influx3xtend", help="MQTT 密码 (默认: influx3xtend)")
    args = parser.parse_args()

    if hasattr(mqtt, "CallbackAPIVersion"):
        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"python_sim_publisher_{int(time.time())}")
    else:
        client = mqtt.Client(client_id=f"python_sim_publisher_{int(time.time())}")
    
    if args.username:
        client.username_pw_set(args.username, args.password)
    
    print(f"[*] 正在连接 MQTT Broker ({args.broker}:{args.port})...")
    try:
        client.connect(args.broker, args.port, 60)
        client.loop_start()
        print("[+] MQTT 连接成功！开始定时发布测试报文...")
    except Exception as e:
        print(f"[-] 连接 MQTT Broker 失败: {e}")
        print("[!] 请确保本地已启动 MQTT Broker (例如 EMQX / Mosquitto，监听 1883 端口)。")
        return

    sent_packets = 0
    try:
        while True:
            now_ns = int(time.time() * 1_000_000_000)

            # 1. 发布低频设备 DEV_REG_001 数据
            reg_payload = generate_regular_payload(now_ns)
            topic_reg = "iot/telemetry/DEV_REG_001"
            client.publish(topic_reg, json.dumps(reg_payload), qos=0)
            print(f"[REG] 已发布低频遥测 1 点数据 -> {topic_reg}")

            # 2. 发布 10kHz 高频设备 DEV_HF_002 波形数据
            hf_payload = generate_high_frequency_payload(now_ns, point_count=10000)
            topic_hf = "iot/telemetry/DEV_HF_002"
            client.publish(topic_hf, json.dumps(hf_payload), qos=0)
            print(f"[HF]  已发布 10kHz 波形 10,000 点数据 -> {topic_hf}")

            sent_packets += 1
            if args.count > 0 and sent_packets >= args.count:
                print(f"[*] 已达到指定发送包数量 ({args.count})，退出模拟器。")
                break

            time.sleep(args.interval_sec)
    except KeyboardInterrupt:
        print("\n[*] 收到用户终止指令，正在退出...")
    finally:
        client.loop_stop()
        client.disconnect()
        print("[+] MQTT 连接已安全关闭。")


if __name__ == "__main__":
    main()
