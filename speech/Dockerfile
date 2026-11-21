FROM python:3.11-slim

# ffmpeg för att avkoda webm/opus/ogg från webbläsare och enheter
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Svensk Piper-röst (NST, medium). Byt med PIPER_VOICE_URL vid build, t.ex. till "Alma".
ARG PIPER_VOICE_URL=https://huggingface.co/rhasspy/piper-voices/resolve/main/sv/sv_SE/nst/medium/sv_SE-nst-medium.onnx
RUN mkdir -p /voices \
    && curl -fsSL -o /voices/sv_SE-nst-medium.onnx "$PIPER_VOICE_URL" \
    && curl -fsSL -o /voices/sv_SE-nst-medium.onnx.json "$PIPER_VOICE_URL.json"

# Ladda ner KB-Whisper i förväg så att första anropet inte tar en evighet
ENV STT_MODEL=KBLab/kb-whisper-small HF_HOME=/models
RUN python -c "from faster_whisper import WhisperModel; WhisperModel('${STT_MODEL}', device='cpu', compute_type='int8')"

COPY app.py .
RUN chgrp -R 0 /app /models /voices && chmod -R g=u /app /models /voices
USER 10001
EXPOSE 8090
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8090"]
