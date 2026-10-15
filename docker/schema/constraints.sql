-- ADR-0002 §6·§9: FK·UNIQUE·인덱스. tables.sql 다음에 건다.
-- 대량 적재 시에는 COPY 뒤에 실행해 적재 속도를 얻고, 걸리는 순간 전체 행을 검증한다.

-- UNIQUE
ALTER TABLE users            ADD CONSTRAINT users_email_key UNIQUE (email);
ALTER TABLE categories       ADD CONSTRAINT categories_name_key UNIQUE (name);
ALTER TABLE products         ADD CONSTRAINT products_model_no_key UNIQUE (model_no);
ALTER TABLE product_sizes    ADD CONSTRAINT product_sizes_product_size_key UNIQUE (product_id, size);
ALTER TABLE orders           ADD CONSTRAINT orders_idempotency_key_key UNIQUE (idempotency_key);
ALTER TABLE inspections      ADD CONSTRAINT inspections_order_item_key UNIQUE (order_item_id);
ALTER TABLE settlements      ADD CONSTRAINT settlements_seller_period_key UNIQUE (seller_id, period_start);
ALTER TABLE user_grade_history ADD CONSTRAINT user_grade_history_user_from_key UNIQUE (user_id, effective_from);
-- 입찰 1개에 살아 있는 체결은 최대 1건(§9.1, 불변식 6)
CREATE UNIQUE INDEX trades_live_buy_bid_key  ON trades (buy_bid_id)  WHERE status <> 'PAYMENT_EXPIRED';
CREATE UNIQUE INDEX trades_live_sell_bid_key ON trades (sell_bid_id) WHERE status <> 'PAYMENT_EXPIRED';
-- 체결 1건에 결제 실패가 아닌 주문 항목은 최대 1건(§9.1)
CREATE UNIQUE INDEX order_items_live_trade_key ON order_items (trade_id) WHERE status <> 'PAYMENT_FAILED';

-- FK
ALTER TABLE user_grade_history     ADD FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE seller_payout_accounts ADD FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE products               ADD FOREIGN KEY (category_id) REFERENCES categories(id);
ALTER TABLE product_sizes          ADD FOREIGN KEY (product_id) REFERENCES products(id);
ALTER TABLE bids                   ADD FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE bids                   ADD FOREIGN KEY (product_size_id) REFERENCES product_sizes(id);
ALTER TABLE bid_events             ADD FOREIGN KEY (bid_id) REFERENCES bids(id);
ALTER TABLE bid_events             ADD FOREIGN KEY (trade_id) REFERENCES trades(id);
ALTER TABLE trades                 ADD FOREIGN KEY (buy_bid_id) REFERENCES bids(id);
ALTER TABLE trades                 ADD FOREIGN KEY (sell_bid_id) REFERENCES bids(id);
ALTER TABLE trades                 ADD FOREIGN KEY (buyer_id) REFERENCES users(id);
ALTER TABLE trades                 ADD FOREIGN KEY (seller_id) REFERENCES users(id);
ALTER TABLE trades                 ADD FOREIGN KEY (product_size_id) REFERENCES product_sizes(id);
ALTER TABLE buyer_warnings         ADD FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE buyer_warnings         ADD FOREIGN KEY (trade_id) REFERENCES trades(id);
ALTER TABLE orders                 ADD FOREIGN KEY (buyer_id) REFERENCES users(id);
ALTER TABLE order_items            ADD FOREIGN KEY (order_id) REFERENCES orders(id);
ALTER TABLE order_items            ADD FOREIGN KEY (trade_id) REFERENCES trades(id);
ALTER TABLE pg_transactions        ADD FOREIGN KEY (order_id) REFERENCES orders(id);
ALTER TABLE refunds                ADD FOREIGN KEY (order_item_id) REFERENCES order_items(id);
ALTER TABLE inspections            ADD FOREIGN KEY (order_item_id) REFERENCES order_items(id);
ALTER TABLE disputes               ADD FOREIGN KEY (order_item_id) REFERENCES order_items(id);
ALTER TABLE point_ledger           ADD FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE point_ledger           ADD FOREIGN KEY (order_item_id) REFERENCES order_items(id);
ALTER TABLE point_ledger           ADD FOREIGN KEY (order_id) REFERENCES orders(id);
ALTER TABLE point_allocations      ADD FOREIGN KEY (entry_id) REFERENCES point_ledger(id);
ALTER TABLE point_allocations      ADD FOREIGN KEY (earn_entry_id) REFERENCES point_ledger(id);
ALTER TABLE point_balances         ADD FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE settlements            ADD FOREIGN KEY (seller_id) REFERENCES users(id);
ALTER TABLE settlement_items       ADD FOREIGN KEY (settlement_id) REFERENCES settlements(id);
ALTER TABLE settlement_items       ADD FOREIGN KEY (order_item_id) REFERENCES order_items(id);
ALTER TABLE seller_penalties       ADD FOREIGN KEY (seller_id) REFERENCES users(id);
ALTER TABLE seller_penalties       ADD FOREIGN KEY (order_item_id) REFERENCES order_items(id);
ALTER TABLE seller_penalties       ADD FOREIGN KEY (settlement_id) REFERENCES settlements(id);

-- 조회 인덱스
CREATE INDEX bids_matching_idx           ON bids (product_size_id, side, status, price, created_at);   -- 매칭 탐색
CREATE INDEX bids_user_idx               ON bids (user_id, status);
CREATE INDEX bid_events_bid_idx          ON bid_events (bid_id);
CREATE INDEX trades_buyer_idx            ON trades (buyer_id, status);
CREATE INDEX trades_seller_idx           ON trades (seller_id, status);
CREATE INDEX trades_due_idx              ON trades (status, payment_due_at);                           -- 결제 기한 배치
CREATE INDEX buyer_warnings_user_idx     ON buyer_warnings (user_id, created_at);
CREATE INDEX orders_buyer_idx            ON orders (buyer_id, created_at);
CREATE INDEX order_items_order_idx       ON order_items (order_id);
CREATE INDEX order_items_status_idx      ON order_items (status);
CREATE INDEX order_items_confirmed_idx   ON order_items (confirmed_at);                                -- 주간 정산·등급
CREATE INDEX pg_transactions_order_idx   ON pg_transactions (order_id);
CREATE INDEX refunds_item_idx            ON refunds (order_item_id);
CREATE INDEX disputes_item_idx           ON disputes (order_item_id);
CREATE INDEX point_ledger_fifo_idx       ON point_ledger (user_id, kind, status, expires_at);          -- FIFO 차감
CREATE INDEX point_ledger_item_idx       ON point_ledger (order_item_id);
CREATE INDEX point_ledger_order_idx      ON point_ledger (order_id);
CREATE INDEX point_allocations_entry_idx ON point_allocations (entry_id);
CREATE INDEX point_allocations_earn_idx  ON point_allocations (earn_entry_id);
CREATE INDEX settlement_items_settle_idx ON settlement_items (settlement_id);
CREATE INDEX settlement_items_item_idx   ON settlement_items (order_item_id);
CREATE INDEX seller_penalties_seller_idx ON seller_penalties (seller_id, settlement_id);
CREATE INDEX user_grade_history_user_idx ON user_grade_history (user_id);
