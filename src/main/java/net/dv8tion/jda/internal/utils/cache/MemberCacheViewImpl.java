/*
 * Copyright 2015 Austin Keener, Michael Ritter, Florian Spieß, and the JDA contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.dv8tion.jda.internal.utils.cache;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.cache.MemberCacheView;
import net.dv8tion.jda.internal.utils.Checks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class MemberCacheViewImpl extends SnowflakeCacheViewImpl<Member> implements MemberCacheView {
    public MemberCacheViewImpl() {
        super(Member.class, Member::getEffectiveName);
    }

    @Override
    public Member getElementById(long id) {
        return get(id);
    }

    @Nonnull
    @Override
    public List<Member> getElementsByUsername(@Nonnull String name, boolean ignoreCase) {
        Checks.notEmpty(name, "Name");
        if (isEmpty()) {
            return List.of();
        }
        List<Member> members = new ArrayList<>();
        forEach(member -> {
            String nick = member.getUser().getName();
            if (equals(ignoreCase, nick, name)) {
                members.add(member);
            }
        });
        return List.copyOf(members);
    }

    @Nonnull
    @Override
    public List<Member> getElementsByNickname(@Nullable String name, boolean ignoreCase) {
        if (isEmpty()) {
            return List.of();
        }
        List<Member> members = new ArrayList<>();
        forEach(member -> {
            String nick = member.getNickname();
            if (nick == null) {
                if (name == null) {
                    members.add(member);
                }
                return;
            }

            if (equals(ignoreCase, nick, name)) {
                members.add(member);
            }
        });
        return List.copyOf(members);
    }

    @Nonnull
    @Override
    public List<Member> getElementsWithRoles(@Nonnull Role... roles) {
        Checks.notNull(roles, "Roles");
        return getElementsWithRoles(List.of(roles));
    }

    @Nonnull
    @Override
    public List<Member> getElementsWithRoles(@Nonnull Collection<Role> roles) {
        Checks.noneNull(roles, "Roles");
        if (isEmpty()) {
            return List.of();
        }

        boolean hasPublicRole = roles.stream().anyMatch(Role::isPublicRole);
        if (hasPublicRole) {
            return asList();
        }

        long[] roleIds = roles.stream().mapToLong(Role::getIdLong).toArray();
        return getElementsWithRoles(roleIds);
    }

    @Nonnull
    @Override
    public List<Member> getElementsWithRoles(@Nonnull long... roleIds) {
        Checks.notNull(roleIds, "Role IDs");
        if (isEmpty()) {
            return List.of();
        }
        if (roleIds.length == 0) {
            return asList();
        }

        List<Member> members = new ArrayList<>();
        if (roleIds.length > 1) {
            forEach(member -> {
                if (member.hasAllRoles(roleIds)) {
                    members.add(member);
                }
            });
        } else {
            long roleId = roleIds[0];
            forEach(member -> {
                if (member.hasRole(roleId)) {
                    members.add(member);
                }
            });
        }
        return List.copyOf(members);
    }

    @Nonnull
    @Override
    public List<Member> getElementsWithAnyRole(@Nonnull Role... roles) {
        Checks.notNull(roles, "Roles");
        return getElementsWithAnyRole(List.of(roles));
    }

    @Nonnull
    @Override
    public List<Member> getElementsWithAnyRole(@Nonnull Collection<Role> roles) {
        Checks.noneNull(roles, "Roles");
        if (isEmpty()) {
            return List.of();
        }

        boolean hasPublicRole = roles.stream().anyMatch(Role::isPublicRole);
        if (hasPublicRole) {
            return asList();
        }

        long[] roleIds = roles.stream().mapToLong(Role::getIdLong).toArray();
        return getElementsWithAnyRole(roleIds);
    }

    @Nonnull
    @Override
    public List<Member> getElementsWithAnyRole(@Nonnull long... roleIds) {
        Checks.notNull(roleIds, "Role IDs");
        if (isEmpty() || roleIds.length == 0) {
            return List.of();
        }

        List<Member> members = new ArrayList<>();
        if (roleIds.length > 1) {
            forEach(member -> {
                if (member.hasAnyRole(roleIds)) {
                    members.add(member);
                }
            });
        } else {
            long roleId = roleIds[0];
            forEach(member -> {
                if (member.hasRole(roleId)) {
                    members.add(member);
                }
            });
        }
        return List.copyOf(members);
    }
}
