FROM python:3.11-slim

# ffmpeg för att avkoda webm/opus/ogg från webbläsare och enheter
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Svenska Piper-röster: nst, lisa och alma. För varje röst tas den första kvalitet som finns.
ARG PIPER_VOICES="nst lisa alma"
ARG PIPER_BASE=https://huggingface.co/rhasspy/piper-voices/resolve/main/sv/sv_SE
RUN mkdir -p /voices && for v in $PIPER_VOICES; do \
      for q in medium high low x_low; do \
        f="sv_SE-$v-$q.onnx"; \
        if curl -fsSL -o "/voices/$f" "$PIPER_BASE/$v/$q/$f" && curl -fsSL -o "/voices/$f.json" "$PIPER_BASE/$v/$q/$f.json"; then \
          echo "Röst: $f"; break; \
        else rm -f "/voices/$f" "/voices/$f.json"; fi; \
      done; \
    done && ls -la /voices && test -n "$(ls /voices/*.onnx)"
ENV DEFAULT_VOICE=sv_SE-nst-medium

# Ladda ner KB-Whisper i förväg så att första anropet inte tar en evighet
ENV STT_MODEL=KBLab/kb-whisper-small HF_HOME=/models
RUN python -c "from faster_whisper import WhisperModel; WhisperModel('${STT_MODEL}', device='cpu', compute_type='int8')"
# Flerspråkig Whisper för glosor på spanska, tyska, franska och engelska (ca 0,5 GB)
ENV STT_MULTI_MODEL=Systran/faster-whisper-small
RUN python -c "from faster_whisper import WhisperModel; WhisperModel('${STT_MULTI_MODEL}', device='cpu', compute_type='int8')"

COPY app.py .
RUN chgrp -R 0 /app /models /voices && chmod -R g=u /app /models /voices
USER 10001
EXPOSE 8090
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8090"]
