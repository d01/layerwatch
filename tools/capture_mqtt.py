#!/usr/bin/env python3
"""Record raw X1C MQTT reports as JSON Lines for use as test fixtures.

Usage: capture_mqtt.py HOST SERIAL ACCESS_CODE SECONDS OUTFILE
Sends one pushall (full state dump) then records incremental reports.
Read-only: never publishes anything except the pushall request.
"""
import json, ssl, sys, time
import paho.mqtt.client as mqtt

host, serial, code, secs, out = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4]), sys.argv[5]
start = time.time()
f = open(out, "w")
count = 0

def on_connect(c, u, flags, rc, props=None):
    c.subscribe(f"device/{serial}/report")
    c.publish(f"device/{serial}/request",
              json.dumps({"pushing": {"sequence_id": "1", "command": "pushall"}}))

def on_message(c, u, msg):
    global count
    count += 1
    f.write(json.dumps({"t": round(time.time() - start, 3),
                        "payload": json.loads(msg.payload)}) + "\n")
    f.flush()

c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
c.username_pw_set("bblp", code)
c.tls_set(cert_reqs=ssl.CERT_NONE)
c.tls_insecure_set(True)
c.on_connect, c.on_message = on_connect, on_message
c.connect(host, 8883)
c.loop_start()
time.sleep(secs)
c.loop_stop()
print(f"{count} messages -> {out}")
