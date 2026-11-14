-- 선택 모델이 본 후보 수. candidates의 그 뒤 번호는 선택 결과를 보고 덧붙인 숙소·예비 후보다.
ALTER TABLE generation_traces ADD COLUMN search_pool_size INT;
