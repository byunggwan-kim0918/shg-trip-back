-- FSQ OS Places 데이터의 category (계층 경로)와 region이 100자를 초과할 수 있음
ALTER TABLE places ALTER COLUMN category TYPE VARCHAR(255);
ALTER TABLE places ALTER COLUMN region TYPE VARCHAR(255);
