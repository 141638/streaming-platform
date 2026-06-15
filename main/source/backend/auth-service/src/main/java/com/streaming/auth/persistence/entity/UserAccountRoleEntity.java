package com.streaming.auth.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_account_role")
@IdClass(UserAccountRoleEntity.Pk.class)
public class UserAccountRoleEntity {

    @Id
    @Column(name = "user_account_id", nullable = false)
    private UUID userAccountId;

    @Id
    @Column(name = "role_slug", nullable = false, length = 64)
    private String roleSlug;

    @Column(name = "granted_at", nullable = false)
    private OffsetDateTime grantedAt;

    @Column(name = "granted_by")
    private UUID grantedBy;

    public static final class Pk implements Serializable {
        private UUID userAccountId;
        private String roleSlug;

        public Pk() {}

        public Pk(UUID userAccountId, String roleSlug) {
            this.userAccountId = userAccountId;
            this.roleSlug = roleSlug;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Pk pk = (Pk) o;
            return Objects.equals(userAccountId, pk.userAccountId) && Objects.equals(roleSlug, pk.roleSlug);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userAccountId, roleSlug);
        }
    }
}
