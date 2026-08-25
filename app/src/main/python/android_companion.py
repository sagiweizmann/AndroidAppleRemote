"""Android TV bridge for the iOS Control Center Apple TV Remote."""
from __future__ import annotations
import asyncio, hashlib, hmac, json, logging, os, secrets, time
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from java import jclass
from atvr4samsung.companion.protocol.appletv import FakeCompanionService, FakeCompanionState
from atvr4samsung.companion.protocol.enums import FrameType, TouchAction
Bridge=jclass("com.sagi.appleremotebridge.CompanionPythonBridge");_LOG=logging.getLogger("AndroidCompanion");_LOOP=None;_SERVER=None;PIN="1337";DEVICE_NAME="Xiaomi TV 2"
def trace(msg):
    try:Bridge.onStatus(msg)
    except Exception:pass
def _atomic_json(path,value):
    path.parent.mkdir(parents=True,exist_ok=True);tmp=path.with_suffix(path.suffix+".tmp")
    with open(tmp,"w",encoding="utf-8") as f:json.dump(value,f,separators=(",",":"));f.flush()
    os.replace(tmp,path)
@dataclass(frozen=True)
class _WindowRecord: pin:str;expires_at:float;generation:str;server_identifier:str;server_generation:str
class AndroidPairingWindow:
    def __init__(self,path,server_identifier,server_generation):self.path=path;self.state_dir=path.parent;self.server_identifier=server_identifier;self.server_generation=server_generation;self._record=_WindowRecord(PIN,time.time()+86400,secrets.token_hex(16),server_identifier,server_generation);self._save()
    def _save(self):_atomic_json(self.path,self._record.__dict__)
    @contextmanager
    def transaction(self):yield
    def active(self):return self._record if time.time()<self._record.expires_at else None
    def active_for_server(self,a,b):
        r=self.active();return r if r and r.server_identifier==a and r.server_generation==b else None
    def mutate_if_current(self,generation,mutation,*,server_identifier=None,server_generation=None):
        r=self.active()
        if not r or r.generation!=generation or(server_identifier is not None and r.server_identifier!=server_identifier)or(server_generation is not None and r.server_generation!=server_generation):return False,None
        return True,mutation()
class AndroidPairedClients:
    def __init__(self,path):self.path=path;self._clients={};self._load()
    def _load(self):
        try:
            with open(self.path,"r",encoding="utf-8")as f:raw=json.load(f)
            self._clients={str(k):str(v) for k,v in raw.items() if isinstance(k,str)and isinstance(v,str)and len(v)==64}if isinstance(raw,dict)else{}
        except Exception:self._clients={}
    def _save(self):_atomic_json(self.path,self._clients)
    def reset_in_progress(self):return False
    def add(self,identifier,ltpk):self.add_locked(identifier,ltpk)
    def add_locked(self,identifier,ltpk):
        if not identifier or not isinstance(ltpk,(bytes,bytearray))or len(ltpk)!=32:raise ValueError("invalid paired client")
        self._clients[str(identifier)]=bytes(ltpk).hex();self._save();trace("PS M5 • iPhone credential saved")
    def ltpk(self,identifier):
        self._load();v=self._clients.get(identifier)
        try:return bytes.fromhex(v)if v else None
        except ValueError:return None
    def authorizes(self,identifier,ltpk):
        e=self.ltpk(identifier);return e is not None and hmac.compare_digest(e,bytes(ltpk))
    def count(self):self._load();return len(self._clients)
    def empty(self):return self.count()==0
class AndroidCompanionService(FakeCompanionService):
    _seq=0
    def __init__(self,state,*,identifier,private_key,server_generation,pairing_window,paired_clients):
        super().__init__(state,device_name=DEVICE_NAME,unique_id=identifier,private_key=private_key,server_identity_generation=server_generation,paired_clients=paired_clients,require_paired=True,pairing_window=pairing_window);type(self)._seq+=1;self.cid=type(self)._seq;self._android_touch_start=None;self._rx=0;self._tx=0
    def connection_made(self,transport):
        peer=transport.get_extra_info("peername");trace(f"TCP #{self.cid} CONNECT • {peer}")
        try:return super().connection_made(transport)
        except Exception as e:trace(f"TCP #{self.cid} CONNECT EXCEPTION • {type(e).__name__}: {e}");raise
    def connection_lost(self,exc):trace(f"TCP #{self.cid} DISCONNECT • rx={self._rx} tx={self._tx} • {type(exc).__name__+': '+str(exc) if exc else 'peer closed'}");return super().connection_lost(exc)
    def data_received(self,data):
        self._rx+=1;trace(f"TCP #{self.cid} RX#{self._rx} • {len(data)} bytes • encrypted={'YES' if self.chacha else 'NO'}")
        try:return super().data_received(data)
        except Exception as e:trace(f"RX EXCEPTION • {type(e).__name__}: {e}");raise
    def send_to_client(self,frame_type,data):
        self._tx+=1;trace(f"TCP #{self.cid} TX#{self._tx} • {getattr(frame_type,'name',str(frame_type))} • encrypted={'YES' if self.chacha else 'NO'}")
        try:return super().send_to_client(frame_type,data)
        except Exception as e:trace(f"TX EXCEPTION • {type(e).__name__}: {e}");raise
    def enable_encryption(self,output_key,input_key):trace(f"TCP #{self.cid} • INSTALL AEAD KEYS");r=super().enable_encryption(output_key,input_key);trace(f"TCP #{self.cid} • ENCRYPTION ACTIVE");return r
    def handle_auth_frame(self,frame_type,data):
        label={FrameType.PS_Start:"PS M1",FrameType.PS_Next:"PS NEXT",FrameType.PV_Start:"PV M1",FrameType.PV_Next:"PV NEXT"}.get(frame_type,f"AUTH {frame_type}")
        try:
            trace(f"PAIR • {label} • {self.authentication_phase.name}");ok=super().handle_auth_frame(frame_type,data);trace(f"PAIR • {label} -> {'OK' if ok else 'FAILED'} • {self.authentication_phase.name}")
            if ok and self.authentication_phase.name=="ENCRYPTED":trace("PAIRING COMPLETE • waiting for first encrypted Companion frame")
            return ok
        except Exception as e:trace(f"PAIR EXCEPTION • {type(e).__name__}: {e}");raise
    def _dispatch(self,command):trace(f"REMOTE • {command}");Bridge.dispatch(command)
    def handle__hidc(self,message):
        before=self.session.latest_button;super().handle__hidc(message);after=self.session.latest_button
        if after and after!=before:
            c={"up":"UP","down":"DOWN","left":"LEFT","right":"RIGHT","select":"OK","menu":"BACK","home":"HOME","play_pause":"PLAY_PAUSE","volume_up":"VOLUME_UP","volume_down":"VOLUME_DOWN","mute":"MUTE"}.get(after)
            if c:self._dispatch(c)
    def handle__mcc(self,message):
        before=self.session.latest_button;super().handle__mcc(message);after=self.session.latest_button
        if after and after!=before:
            c={"play":"PLAY_PAUSE","pause":"PLAY_PAUSE","next":"MEDIA_NEXT","previous":"MEDIA_PREVIOUS"}.get(after)
            if c:self._dispatch(c)
    def handle__touchstart(self,message):self._android_touch_start=None;super().handle__touchstart(message)
    def handle__touchstop(self,message):self._android_touch_start=None;super().handle__touchstop(message)
    def handle__hidt(self,message):
        content=message.get("_c",{});phase=int(content.get("_tPh",-1));x=int(content.get("_cx",0));y=int(content.get("_cy",0))
        try:action=TouchAction(phase)
        except Exception:action=None
        if action==TouchAction.Press:self._android_touch_start=(x,y)
        elif action==TouchAction.Click:self._dispatch("OK");self._android_touch_start=None
        elif action==TouchAction.Release:
            start=self._android_touch_start;self._android_touch_start=None
            if start:
                dx,dy=x-start[0],y-start[1]
                if abs(dx)>=28 or abs(dy)>=28:self._dispatch(("RIGHT"if dx>0 else"LEFT")if abs(dx)>abs(dy)else("DOWN"if dy>0 else"UP"))
        super().handle__hidt(message)
def run_server(identifier):
    global _LOOP,_SERVER
    if _LOOP is not None:return
    # v4 is a clean identity/state namespace so iOS cannot reuse Xiaomi TV credentials.
    state_dir=Path(str(Bridge.getStateDir()))/"companion-xiaomi-tv2-v4";state_dir.mkdir(parents=True,exist_ok=True)
    private_key=hashlib.sha256(("AndroidAppleRemote:XiaomiTV2:v4:"+identifier).encode()).digest();server_generation=hashlib.sha256(("generation:XiaomiTV2:v4:"+identifier).encode()).hexdigest()[:32]
    window=AndroidPairingWindow(state_dir/"pairing-window.json",identifier,server_generation);clients=AndroidPairedClients(state_dir/"paired-clients.json");state=FakeCompanionState();trace(f"STATE • {DEVICE_NAME} • PIN {PIN} • paired clients {clients.count()}")
    loop=asyncio.new_event_loop();asyncio.set_event_loop(loop);_LOOP=loop
    def factory():return AndroidCompanionService(state,identifier=identifier,private_key=private_key,server_generation=server_generation,pairing_window=window,paired_clients=clients)
    server=loop.run_until_complete(loop.create_server(factory,"0.0.0.0",0));_SERVER=server;port=int(server.sockets[0].getsockname()[1]);Bridge.onServerReady(port);trace(f"{DEVICE_NAME} READY • port {port}")
    try:loop.run_forever()
    finally:server.close();loop.run_until_complete(server.wait_closed());loop.close();_SERVER=None;_LOOP=None
def stop_server():
    if _LOOP is not None:_LOOP.call_soon_threadsafe(_LOOP.stop)
