"""
B#NN (B Hash Neural Network) - Flask API Server
Connects Ollama AI model to the BLE Gateway
"""

import json
import logging
import time
from flask import Flask, request, jsonify
import requests

# ── config ──────────────────────────────────────────────────────────────────
OLLAMA_URL   = "http://localhost:11434/api/generate"
MODEL_NAME   = "phi3"          # change to llama3, mistral, etc.
MAX_TOKENS   = 512
TEMPERATURE  = 0.7
HOST         = "0.0.0.0"
PORT         = 5000

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s"
)
log = logging.getLogger("B#NN-API")

app = Flask(__name__)

# ── health check ─────────────────────────────────────────────────────────────
@app.route("/health", methods=["GET"])
def health():
    """Quick check – is the server alive?"""
    return jsonify({"status": "ok", "model": MODEL_NAME, "server": "B#NN"})


# ── main chat endpoint ────────────────────────────────────────────────────────
@app.route("/chat", methods=["POST"])
def chat():
    """
    Accepts:  {"prompt": "...", "device_id": "phone_1"}
    Returns:  {"response": "...", "device_id": "...", "latency_ms": ...}
    """
    data = request.get_json(silent=True)
    if not data or "prompt" not in data:
        return jsonify({"error": "Missing 'prompt' field"}), 400

    prompt    = data["prompt"].strip()
    device_id = data.get("device_id", "unknown")

    if not prompt:
        return jsonify({"error": "Prompt cannot be empty"}), 400

    log.info(f"[{device_id}] Prompt: {prompt[:80]}")
    t0 = time.time()

    try:
        ai_resp = query_ollama(prompt)
    except Exception as e:
        log.error(f"Ollama error: {e}")
        return jsonify({"error": f"AI model error: {str(e)}"}), 503

    latency = int((time.time() - t0) * 1000)
    log.info(f"[{device_id}] Response ready ({latency} ms)")

    return jsonify({
        "response":   ai_resp,
        "device_id":  device_id,
        "latency_ms": latency,
        "model":      MODEL_NAME,
    })


# ── model info endpoint ───────────────────────────────────────────────────────
@app.route("/model", methods=["GET"])
def model_info():
    """Returns which AI model is currently loaded."""
    try:
        r = requests.get("http://localhost:11434/api/tags", timeout=3)
        models = r.json().get("models", [])
        return jsonify({"active_model": MODEL_NAME, "available": models})
    except Exception:
        return jsonify({"active_model": MODEL_NAME, "available": []})


# ── internal helper ───────────────────────────────────────────────────────────
def query_ollama(prompt: str) -> str:
    """Send prompt to Ollama and return the full response string."""
    payload = {
        "model":  MODEL_NAME,
        "prompt": prompt,
        "stream": False,
        "options": {
            "num_predict": MAX_TOKENS,
            "temperature": TEMPERATURE,
        }
    }
    r = requests.post(OLLAMA_URL, json=payload, timeout=120)
    r.raise_for_status()
    return r.json().get("response", "").strip()


# ── entry point ───────────────────────────────────────────────────────────────
if __name__ == "__main__":
    log.info(f"B#NN Flask API starting on {HOST}:{PORT} | model={MODEL_NAME}")
    app.run(host=HOST, port=PORT, debug=False)
