-- Refresh Token 재사용 감지를 위한 revoked 컬럼 추가
-- Rotation 시 기존 토큰을 삭제하지 않고 revoked=true로 마킹
-- revoked된 토큰으로 재접근 시 탈취로 판단하여 전체 세션 무효화
ALTER TABLE refresh_tokens ADD COLUMN revoked BOOLEAN NOT NULL DEFAULT FALSE;
