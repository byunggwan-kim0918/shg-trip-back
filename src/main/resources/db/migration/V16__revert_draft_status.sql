-- V15에서 FINALIZED로 변경된 일정을 DRAFT로 복원
-- 정책 변경: 사용자가 직접 확정 버튼을 눌러야 FINALIZED로 전환
UPDATE itineraries SET status = 'DRAFT' WHERE status = 'FINALIZED';
