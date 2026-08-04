-- 찜 목록(wishlist) 기능 제거에 따른 테이블 삭제.
-- V8에서 생성된 user_place_wishlists를 forward drop한다 (V8은 이미 적용됐으므로 편집 금지).
-- Place 엔티티와 커플링 없음(@OneToMany 없음) → 안전.
DROP TABLE IF EXISTS user_place_wishlists;
