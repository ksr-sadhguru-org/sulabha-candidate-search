FROM python:3.13-slim
WORKDIR /app
RUN adduser --system --no-create-home appuser
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
USER appuser
ENV PORT=4201
EXPOSE 4201
HEALTHCHECK --interval=30s --timeout=10s --start-period=20s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:4201/search')" || exit 1
CMD ["python", "-m", "app.main"]
