"""pluggis-speech: tal till text (KB-Whisper via faster-whisper) och text till tal (Piper).

Körs lokalt i hemlabbet bredvid Pluggis, så att elevens röst aldrig lämnar huset.

    POST /stt   multipart, fält "audio" (wav, webm/opus, ogg, mp3 …)  → {"text": "...", "seconds": 0.42}
    POST /tts   {"text": "Hej!", "voice": "sv_SE-lisa-medium", "length_scale": 1.0} → audio/wav
    GET  /voices                                                       → [{"id": "sv_SE-nst-medium", "name": "nst", ...}]
    GET  /health                                                       → {"ok": true, ...}
"""

import io
import os
import shutil
import subprocess
import tempfile
import time
import wave

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel

STT_MODEL = os.getenv("STT_MODEL", "KBLab/kb-whisper-small")
STT_DEVICE = os.getenv("STT_DEVICE", "cpu")           # "cuda" om det finns ett grafikkort
STT_COMPUTE = os.getenv("STT_COMPUTE_TYPE", "int8")   # "float16" på GPU
STT_BEAM = int(os.getenv("STT_BEAM_SIZE", "1"))       # 1 = snabbast
VOICES_DIR = os.getenv("VOICES_DIR", "/voices")
DEFAULT_VOICE = os.getenv("DEFAULT_VOICE", "sv_SE-nst-medium")
PIPER_LENGTH_SCALE = float(os.getenv("PIPER_LENGTH_SCALE", "1.0"))  # >1 = långsammare tal
MAX_TTS_CHARS = 1200

app = FastAPI(title="pluggis-speech")

# ---- Tal till text ----------------------------------------------------------

_whisper = None


def whisper():
    global _whisper
    if _whisper is None:
        from faster_whisper import WhisperModel
        _whisper = WhisperModel(STT_MODEL, device=STT_DEVICE, compute_type=STT_COMPUTE)
    return _whisper


@app.post("/stt")
async def stt(audio: UploadFile = File(...)):
    data = await audio.read()
    if not data:
        raise HTTPException(400, "Tomt ljud")
    suffix = os.path.splitext(audio.filename or "audio.wav")[1] or ".wav"
    start = time.perf_counter()
    with tempfile.NamedTemporaryFile(suffix=suffix) as f:
        f.write(data)
        f.flush()
        segments, _info = whisper().transcribe(
            f.name,
            language="sv",
            beam_size=STT_BEAM,
            vad_filter=True,
            condition_on_previous_text=False,
        )
        text = " ".join(s.text.strip() for s in segments).strip()
    return {"text": text, "seconds": round(time.perf_counter() - start, 3)}


# ---- Text till tal ----------------------------------------------------------

_voices: dict = {}


def available_voices() -> list[dict]:
    """Alla Piper-röster i VOICES_DIR, t.ex. sv_SE-lisa-medium.onnx."""
    out = []
    if os.path.isdir(VOICES_DIR):
        for f in sorted(os.listdir(VOICES_DIR)):
            if f.endswith(".onnx"):
                vid = f[: -len(".onnx")]
                parts = vid.split("-")
                out.append({
                    "id": vid,
                    "name": parts[1] if len(parts) >= 3 else vid,
                    "quality": parts[2] if len(parts) >= 3 else None,
                    "default": vid == DEFAULT_VOICE,
                })
    return out


def resolve_voice(voice_id: str | None) -> str:
    ids = [v["id"] for v in available_voices()]
    if voice_id and voice_id in ids:
        return voice_id
    if DEFAULT_VOICE in ids:
        return DEFAULT_VOICE
    if ids:
        return ids[0]
    raise HTTPException(500, f"Inga röster i {VOICES_DIR}")


def piper_voice(voice_id: str):
    """Laddar en röst en gång (snabbt). False = använd CLI (äldre/annan piper-version)."""
    if voice_id not in _voices:
        try:
            from piper import PiperVoice
            _voices[voice_id] = PiperVoice.load(os.path.join(VOICES_DIR, voice_id + ".onnx"))
        except Exception:  # noqa: BLE001
            _voices[voice_id] = False
    return _voices[voice_id]


class TtsRequest(BaseModel):
    text: str
    voice: str | None = None
    length_scale: float | None = None


def _synthesize_python(voice, text: str, length_scale: float) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wav:
        if hasattr(voice, "synthesize_wav"):          # piper-tts >= 1.3
            try:
                from piper import SynthesisConfig
                voice.synthesize_wav(text, wav, syn_config=SynthesisConfig(length_scale=length_scale))
            except ImportError:
                voice.synthesize_wav(text, wav)
        else:                                          # piper-tts 1.2
            voice.synthesize(text, wav, length_scale=length_scale)
    return buf.getvalue()


def _synthesize_cli(voice_id: str, text: str, length_scale: float) -> bytes:
    exe = shutil.which("piper")
    if not exe:
        raise HTTPException(500, "Piper saknas")
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "out.wav")
        subprocess.run(
            [exe, "--model", os.path.join(VOICES_DIR, voice_id + ".onnx"),
             "--length_scale", str(length_scale), "--output_file", out],
            input=text.encode("utf-8"), check=True, capture_output=True, timeout=30,
        )
        with open(out, "rb") as f:
            return f.read()


@app.get("/voices")
def voices():
    return available_voices()


@app.post("/tts")
def tts(req: TtsRequest):
    text = (req.text or "").strip()
    if not text:
        raise HTTPException(400, "Tom text")
    text = text[:MAX_TTS_CHARS]
    scale = req.length_scale or PIPER_LENGTH_SCALE
    scale = max(0.6, min(1.8, scale))
    voice_id = resolve_voice(req.voice)
    voice = piper_voice(voice_id)
    wav = _synthesize_python(voice, text, scale) if voice else _synthesize_cli(voice_id, text, scale)
    return Response(content=wav, media_type="audio/wav", headers={"X-Voice": voice_id})


@app.get("/health")
def health():
    return {"ok": True, "stt_model": STT_MODEL, "default_voice": DEFAULT_VOICE,
            "voices": [v["id"] for v in available_voices()]}
