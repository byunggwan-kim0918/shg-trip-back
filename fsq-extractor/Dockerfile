FROM python:3.12-slim

WORKDIR /app

# 시스템 의존성 (DuckDB 빌드에 필요)
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY extract.py .

# 비root 실행
RUN groupadd -r extractor && useradd -r -g extractor extractor
RUN mkdir -p /tmp && chown extractor:extractor /tmp
USER extractor

CMD ["python", "extract.py"]
