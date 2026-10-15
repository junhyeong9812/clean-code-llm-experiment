-- 빈 개발 DB에 같은 스키마를 두 DB에 건다(ADR-0002). 시드 이미지는 tools/seedgen/load.sh 가 따로 만든다.
\set ON_ERROR_STOP on
\connect ccle_god ccle
\i /schema/tables.sql
\i /schema/constraints.sql
\connect ccle_layered ccle
\i /schema/tables.sql
\i /schema/constraints.sql
