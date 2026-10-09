/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
SET NAMES utf8mb4;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE='NO_AUTO_VALUE_ON_ZERO', SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

CREATE database if NOT EXISTS `xlc_agent_starter` default character set utf8mb4 collate utf8mb4_0900_ai_ci;
use `xlc_agent_starter`;

# 转储表 chat_message
# ------------------------------------------------------------

DROP TABLE IF EXISTS `chat_message`;

CREATE TABLE `chat_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(64) NOT NULL COMMENT '会话ID',
  `role` varchar(20) NOT NULL COMMENT '角色: user/assistant/tool/system',
  `content` text COMMENT '消息内容',
  `tool_name` varchar(100) DEFAULT NULL COMMENT '工具名称',
  `tool_call_id` varchar(100) DEFAULT NULL COMMENT '工具调用ID',
  `priority` varchar(20) DEFAULT 'MEDIUM' COMMENT '优先级: CRITICAL/HIGH/MEDIUM/LOW',
  `token_count` int DEFAULT '0' COMMENT '预估 token 数',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session_time` (`session_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话消息';



# 转储表 chat_milestone
# ------------------------------------------------------------

DROP TABLE IF EXISTS `chat_milestone`;

CREATE TABLE `chat_milestone` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(64) NOT NULL COMMENT '会话ID',
  `type` varchar(30) NOT NULL COMMENT '类型: TASK_CHANGE/ERROR/DECISION/...',
  `content` text COMMENT '内容摘要',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session_time` (`session_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话里程碑';



# 转储表 chat_session
# ------------------------------------------------------------

DROP TABLE IF EXISTS `chat_session`;

CREATE TABLE `chat_session` (
  `id` varchar(64) NOT NULL COMMENT '会话ID',
  `agent_id` varchar(64) NOT NULL COMMENT '智能体ID',
  `user_id` varchar(64) NOT NULL COMMENT '用户ID',
  `title` varchar(200) DEFAULT NULL COMMENT '会话标题',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `message_count` int DEFAULT '0' COMMENT '消息数量',
  PRIMARY KEY (`id`),
  KEY `idx_user_agent` (`user_id`,`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话会话';



# 转储表 core_memory
# ------------------------------------------------------------

DROP TABLE IF EXISTS `core_memory`;

CREATE TABLE `core_memory` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '用户ID',
  `scope` varchar(20) NOT NULL COMMENT '作用域: user/session',
  `category` varchar(30) NOT NULL COMMENT '分类: Rule/Preference/Decision/Correction/Fact',
  `title` varchar(200) NOT NULL COMMENT '记忆标题',
  `keywords` varchar(500) DEFAULT NULL COMMENT '关键词(逗号分隔)',
  `content` text COMMENT '记忆正文',
  `priority` int NOT NULL DEFAULT '3' COMMENT '优先级1-5(越高越重要)',
  `source_session_id` varchar(64) DEFAULT NULL COMMENT '来源会话ID',
  `use_count` int NOT NULL DEFAULT '1' COMMENT '使用次数',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `last_used_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_priority` (`user_id`,`priority`),
  KEY `idx_user_last_used` (`user_id`,`last_used_at`),
  KEY `idx_keywords` (`keywords`(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='核心记忆表(对话内学习+跨会话记忆)';



# 转储表 long_term_memory
# ------------------------------------------------------------

DROP TABLE IF EXISTS `long_term_memory`;

CREATE TABLE `long_term_memory` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) NOT NULL COMMENT '用户ID',
  `session_id` varchar(64) DEFAULT NULL COMMENT '来源会话ID',
  `memory_type` varchar(50) NOT NULL COMMENT '记忆类型',
  `memory_key` varchar(128) NOT NULL COMMENT '记忆去重键',
  `content` text NOT NULL COMMENT '记忆内容',
  `keywords` varchar(512) DEFAULT NULL COMMENT '关键词集合',
  `source_role` varchar(20) DEFAULT NULL COMMENT '来源角色',
  `confidence` decimal(5,2) DEFAULT '0.50' COMMENT '置信度',
  `hit_count` int DEFAULT '1' COMMENT '命中/更新次数',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_type_key` (`user_id`,`memory_type`,`memory_key`),
  KEY `idx_user_time` (`user_id`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='长期记忆';


/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
