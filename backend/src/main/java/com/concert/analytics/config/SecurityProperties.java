package com.concert.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "concert.security")
public class SecurityProperties {

    private final Admin admin = new Admin();
    private final Viewer viewer = new Viewer();
    private String allowedOrigins = "";

    public Admin getAdmin() {
        return admin;
    }

    public Viewer getViewer() {
        return viewer;
    }

    public String getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public static class Admin {
        private boolean enabled;
        private String username = "admin";
        private String password = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    /**
     * 只读账号：登录后看大屏和导出，看不到操作日志和缓存管理。
     */
    public static class Viewer {
        private String username = "viewer";
        private String password = "";

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
