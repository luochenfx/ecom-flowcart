-- ecom-flowcart：Temporal Server 依赖库（postgres 容器首次初始化时以 superuser 执行一次）。
-- 与 docker/temporal-db-init.sh 默认库名一致；改名须同步脚本与 Temporal Server 侧配置。
CREATE DATABASE temporal;
CREATE DATABASE temporal_visibility;
