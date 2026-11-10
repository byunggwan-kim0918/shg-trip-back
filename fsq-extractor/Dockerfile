FROM python:3.12-slim

WORKDIR /app

# 시스템 의존성 (DuckDB 빌드에 필요)
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    && rm -rf /var/lib/apt/lists/*

# 빌드 컨텍스트는 shg-trip-back이다. 여행지 목록을 백엔드와 같은 파일로 쓴다.
COPY fsq-extractor/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY fsq-extractor/extract.py .
COPY src/main/resources/destinations.json .

# 비root 실행 (DuckDB extension 설치에 home directory 필요)
RUN groupadd -r extractor && useradd -r -g extractor -m extractor
RUN mkdir -p /tmp && chown extractor:extractor /tmp
USER extractor

CMD ["python", "extract.py"]
