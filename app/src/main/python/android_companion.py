"""Android TV bridge for the iOS Control Center Apple TV Remote."""
from __future__ import annotations
import asyncio, hashlib, hmac, json, os, secrets, time
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from java import jclass
from atvr4samsung.companion.protocol.appletv import FakeCompanionService, FakeCompanionState
from atvr4samsung.companion.protocol.enums import FrameType, TouchAction
Bridge=jclass("com.sagi.appleremotebridge.CompanionPythonBridge");_LOOP=None;_SERVER=None;PIN="1337"
def trace(m):
 try:Bridge.onStatus(m)
 except Exception:pass
def _atomic_json(p,v):
 p.parent.mkdir(parents=True,exist_ok=True);t=p.with_suffix(p.suffix+".tmp")
 with open(t,"w",encoding="utf-8")as f:json.dump(v,f,separators=(",",":"));f.flush()
 os.replace(t,p)
@dataclass(frozen=True)
class _WindowRecord:pin:str;expires_at:float;generation:str;server_identifier:str;server_generation:str
class AndroidPairingWindow:
 def __init__(s,p,i,g):s.path=p;s.state_dir=p.parent;s.server_identifier=i;s.server_generation=g;s._record=_WindowRecord(PIN,time.time()+86400,secrets.token_hex(16),i,g);s._save()
 def _save(s):_atomic_json(s.path,s._record.__dict__)
 @contextmanager
 def transaction(s):yield
 def active(s):return s._record if time.time()<s._record.expires_at else None
 def active_for_server(s,a,b):
  r=s.active();return r if r and r.server_identifier==a and r.server_generation==b else None
 def mutate_if_current(s,g,m,*,server_identifier=None,server_generation=None):
  r=s.active()
  if not r or r.generation!=g or(server_identifier is not None and r.server_identifier!=server_identifier)or(server_generation is not None and r.server_generation!=server_generation):return False,None
  return True,m()
class AndroidPairedClients:
 def __init__(s,p):s.path=p;s._clients={};s._load()
 def _load(s):
  try:
   with open(s.path,"r",encoding="utf-8")as f:r=json.load(f)
   s._clients={str(k):str(v)for k,v in r.items()if isinstance(k,str)and isinstance(v,str)and len(v)==64}if isinstance(r,dict)else{}
  except Exception:s._clients={}
 def _save(s):_atomic_json(s.path,s._clients)
 def reset_in_progress(s):return False
 def add(s,i,k):s.add_locked(i,k)
 def add_locked(s,i,k):
  if not i or not isinstance(k,(bytes,bytearray))or len(k)!=32:raise ValueError("invalid paired client")
  s._clients[str(i)]=bytes(k).hex();s._save();trace("PS M5 • iPhone credential saved")
 def ltpk(s,i):
  s._load();v=s._clients.get(i)
  try:return bytes.fromhex(v)if v else None
  except ValueError:return None
 def authorizes(s,i,k):
  e=s.ltpk(i);return e is not None and hmac.compare_digest(e,bytes(k))
 def count(s):s._load();return len(s._clients)
 def empty(s):return s.count()==0
class AndroidCompanionService(FakeCompanionService):
 _seq=0
 def __init__(s,state,*,name,identifier,private_key,server_generation,pairing_window,paired_clients):super().__init__(state,device_name=name,unique_id=identifier,private_key=private_key,server_identity_generation=server_generation,paired_clients=paired_clients,require_paired=True,pairing_window=pairing_window);type(s)._seq+=1;s.cid=type(s)._seq;s._android_touch_start=None;s._rx=0;s._tx=0
 def connection_made(s,t):trace(f"TCP #{s.cid} CONNECT • {t.get_extra_info('peername')}");return super().connection_made(t)
 def connection_lost(s,e):trace(f"TCP #{s.cid} DISCONNECT • rx={s._rx} tx={s._tx} • {e or 'peer closed'}");return super().connection_lost(e)
 def data_received(s,d):s._rx+=1;trace(f"TCP #{s.cid} RX#{s._rx} • {len(d)} bytes • encrypted={'YES'if s.chacha else'NO'}");return super().data_received(d)
 def send_to_client(s,f,d):s._tx+=1;trace(f"TCP #{s.cid} TX#{s._tx} • {getattr(f,'name',f)} • encrypted={'YES'if s.chacha else'NO'}");return super().send_to_client(f,d)
 def enable_encryption(s,o,i):trace(f"TCP #{s.cid} • INSTALL AEAD KEYS");r=super().enable_encryption(o,i);trace(f"TCP #{s.cid} • ENCRYPTION ACTIVE");return r
 def handle_auth_frame(s,f,d):
  l={FrameType.PS_Start:"PS M1",FrameType.PS_Next:"PS NEXT",FrameType.PV_Start:"PV M1",FrameType.PV_Next:"PV NEXT"}.get(f,str(f));trace(f"PAIR • {l} • {s.authentication_phase.name}");ok=super().handle_auth_frame(f,d);trace(f"PAIR • {l} -> {'OK'if ok else'FAILED'} • {s.authentication_phase.name}");return ok
 def _dispatch(s,c):trace(f"REMOTE • {c}");Bridge.dispatch(c)
 def handle__hidc(s,m):
  b=s.session.latest_button;super().handle__hidc(m);a=s.session.latest_button
  if a and a!=b:
   c={"up":"UP","down":"DOWN","left":"LEFT","right":"RIGHT","select":"OK","menu":"BACK","home":"HOME","play_pause":"PLAY_PAUSE","volume_up":"VOLUME_UP","volume_down":"VOLUME_DOWN","mute":"MUTE"}.get(a)
   if c:s._dispatch(c)
 def handle__mcc(s,m):
  b=s.session.latest_button;super().handle__mcc(m);a=s.session.latest_button
  if a and a!=b:
   c={"play":"PLAY_PAUSE","pause":"PLAY_PAUSE","next":"MEDIA_NEXT","previous":"MEDIA_PREVIOUS"}.get(a)
   if c:s._dispatch(c)
 def handle__touchstart(s,m):s._android_touch_start=None;super().handle__touchstart(m)
 def handle__touchstop(s,m):s._android_touch_start=None;super().handle__touchstop(m)
 def handle__hidt(s,m):
  c=m.get("_c",{});p=int(c.get("_tPh",-1));x=int(c.get("_cx",0));y=int(c.get("_cy",0))
  try:a=TouchAction(p)
  except Exception:a=None
  if a==TouchAction.Press:s._android_touch_start=(x,y)
  elif a==TouchAction.Click:s._dispatch("OK");s._android_touch_start=None
  elif a==TouchAction.Release:
   q=s._android_touch_start;s._android_touch_start=None
   if q:
    dx,dy=x-q[0],y-q[1]
    if abs(dx)>=28 or abs(dy)>=28:s._dispatch(("RIGHT"if dx>0 else"LEFT")if abs(dx)>abs(dy)else("DOWN"if dy>0 else"UP"))
  super().handle__hidt(m)
def run_server(identifier,name,generation):
 global _LOOP,_SERVER
 if _LOOP is not None:return
 generation=int(generation);state_dir=Path(str(Bridge.getStateDir()))/f"companion-runtime-{generation}-{identifier}";state_dir.mkdir(parents=True,exist_ok=True);private_key=hashlib.sha256(f"AndroidAppleRemote:runtime:{generation}:{identifier}".encode()).digest();sg=hashlib.sha256(f"generation:runtime:{generation}:{identifier}".encode()).hexdigest()[:32];w=AndroidPairingWindow(state_dir/"pairing-window.json",identifier,sg);clients=AndroidPairedClients(state_dir/"paired-clients.json");state=FakeCompanionState();trace(f"STATE • {name} • PIN {PIN} • paired clients {clients.count()}");loop=asyncio.new_event_loop();asyncio.set_event_loop(loop);_LOOP=loop
 def factory():return AndroidCompanionService(state,name=name,identifier=identifier,private_key=private_key,server_generation=sg,pairing_window=w,paired_clients=clients)
 server=loop.run_until_complete(loop.create_server(factory,"0.0.0.0",0));_SERVER=server;port=int(server.sockets[0].getsockname()[1]);Bridge.onServerReady(port);trace(f"{name} READY • port {port}")
 try:loop.run_forever()
 finally:server.close();loop.run_until_complete(server.wait_closed());loop.close();_SERVER=None;_LOOP=None
def stop_server():
 if _LOOP is not None:_LOOP.call_soon_threadsafe(_LOOP.stop)
