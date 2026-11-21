"""pluggis-speech: tal till text (KB-Whisper via faster-whisper) och text till tal (Piper).

Körs lokalt i hemlabbet bredvid Pluggis, så att elevens röst aldrig lämnar huset.

    POST /stt   multipart, fält "audio" (wav, webm/opus, ogg, mp3 …)  → {"text": "...", "seconds": 0.42}
    POST /tts   {"text": "Hej!", "length_scale": 1.0}                 → audio/wav
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
PIPER_MODEL = os.getenv("PIPER_MODEL", "/voices/sv_SE-nst-medium.onnx")
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

_voice = None


def piper_voice():
    """Laddar Piper-rösten en gång (snabbt). Faller tillbaka på CLI om Python-API:t saknas."""
    global _voice
    if _voice is None:
        try:
            from piper import PiperVoice
            _voice = PiperVoice.load(PIPER_MODEL)
        except Exception:  # noqa: BLE001 – äldre/annan piper-version
            _voice = False
    return _voice


class TtsRequest(BaseModel):
    text: str
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


def _synthesize_cli(text: str, length_scale: float) -> bytes:
    exe = shutil.which("piper")
    if not exe:
        raise HTTPException(500, "Piper saknas")
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "out.wav")
        subprocess.run(
            [exe, "--model", PIPER_MODEL, "--length_scale", str(length_scale), "--output_file", out],
            input=text.encode("utf-8"), check=True, capture_output=True, timeout=30,
        )
        with open(out, "rb") as f:
            return f.read()


@app.post("/tts")
def tts(req: TtsRequest):
    text = (req.text or "").strip()
    if not text:
        raise HTTPException(400, "Tom text")
    text = text[:MAX_TTS_CHARS]
    scale = req.length_scale or PIPER_LENGTH_SCALE
    voice = piper_voice()
    wav = _synthesize_python(voice, text, scale) if voice else _synthesize_cli(text, scale)
    return Response(content=wav, media_type="audio/wav")


@app.get("/health")
def health():
    return {"ok": True, "stt_model": STT_MODEL, "piper_model": PIPER_MODEL,
            "piper_model_exists": os.path.exists(PIPER_MODEL)}
