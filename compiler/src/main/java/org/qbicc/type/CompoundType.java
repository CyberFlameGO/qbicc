package org.qbicc.type;

import io.smallrye.common.constraint.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Supplier;

/**
 *
 */
public final class CompoundType extends ValueType {

    private final Tag tag;
    private final String name;
    private final long size;
    private final int align;
    private final boolean complete;
    private volatile Supplier<List<Member>> membersResolver;
    private volatile List<Member> members;

    CompoundType(final TypeSystem typeSystem, final Tag tag, final String name, final Supplier<List<Member>> membersResolver, final long size, final int overallAlign) {
        super(typeSystem, (int) size * 19 + Integer.numberOfTrailingZeros(overallAlign));
        // name/tag do not contribute to hash or equality
        this.tag = tag;
        this.name = name;
        // todo: assert size ≥ end of last member w/alignment etc.
        this.size = size;
        assert Integer.bitCount(overallAlign) == 1;
        this.align = overallAlign;
        this.membersResolver = membersResolver;
        this.complete = true;
    }

    CompoundType(final TypeSystem typeSystem, final Tag tag, final String name) {
        super(typeSystem, 0);
        this.tag = tag;
        this.name = name;
        this.size = 0;
        this.align = 1;
        this.complete = false;
        this.members = List.of();
    }

    public boolean isAnonymous() {
        return name == null;
    }

    public String getName() {
        final String name = this.name;
        return name == null ? "<anon>" : name;
    }

    public List<Member> getMembers() {
        List<Member> members = this.members;
        if (members == null) {
            synchronized (this) {
                members = this.members;
                if (members == null) {
                    members = this.members = membersResolver.get();
                    membersResolver = null;
                }
            }
        }
        return members;
    }

    public Tag getTag() {
        return tag;
    }

    public int getMemberCount() {
        return getMembers().size();
    }

    public Member getMember(int index) throws IndexOutOfBoundsException {
        return getMembers().get(index);
    }

    public Member getMember(String name) {
        Assert.assertFalse(isAnonymous()); /* anonymous structs do not have member names */
        List<Member> members = getMembers();
        for (Member m : members) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new NoSuchElementException("No member named '" + name + "' found in " + this.toFriendlyString());
    }

    public boolean isComplete() {
        return complete;
    }

    public long getSize() {
        return size;
    }

    public int getAlign() {
        return align;
    }

    public boolean equals(final ValueType other) {
        return other instanceof CompoundType ct && equals(ct);
    }

    public boolean equals(final CompoundType other) {
        return this == other || super.equals(other) && Objects.equals(name, other.name) && size == other.size && align == other.align && getMembers().equals(other.getMembers());
    }

    public StringBuilder toString(final StringBuilder b) {
        super.toString(b);
        b.append("compound ");
        if (tag != Tag.NONE) {
            b.append(tag).append(' ');
        }
        return b.append(getName());
    }

    public StringBuilder toFriendlyString(final StringBuilder b) {
        b.append("compound.");
        b.append(tag.toString());
        b.append('.');
        b.append(getName());
        return b;
    }

    public static CompoundType.Builder builder(TypeSystem typeSystem) {
        return new Builder(typeSystem);
    }

    public static final class Member implements Comparable<Member> {
        private final int hashCode;
        private final String name;
        private final ValueType type;
        private final int offset;
        private final int align;

        Member(final String name, final ValueType type, final int offset, final int align) {
            this.name = name;
            this.type = type;
            this.offset = offset;
            this.align = Math.max(align, type.getAlign());
            assert Integer.bitCount(align) == 1;
            hashCode = (Objects.hash(name, type) * 19 + offset) * 19 + Integer.numberOfTrailingZeros(align);
        }

        public String getName() {
            return name;
        }

        public ValueType getType() {
            return type;
        }

        public int getOffset() {
            return offset;
        }

        public int getAlign() {
            return align;
        }

        public int hashCode() {
            return hashCode;
        }

        public String toString() {
            return toString(new StringBuilder()).toString();
        }

        public StringBuilder toString(final StringBuilder b) {
            type.toString(b).append(' ').append(name).append('@').append(offset);
            if (align > 1) {
                b.append(" align=").append(align);
            }
            return b;
        }

        public boolean equals(final Object obj) {
            return obj instanceof Member m && equals(m);
        }

        public boolean equals(final Member other) {
            return other == this || other != null && hashCode == other.hashCode && offset == other.offset && align == other.align && Objects.equals(name, other.name) && type.equals(other.type);
        }

        public int compareTo(final Member o) {
            // compare offset
            int res = Integer.compare(offset, o.offset);
            // at same offset? if so, compare size
            if (res == 0) res = Long.compare(o.type.getSize(), type.getSize());
            // at same offset *and* same size? if so, strive for *some* predictable order...
            if (res == 0) res = Integer.compare(hashCode, o.hashCode);
            return res;
        }
    }

    public enum Tag {
        NONE("untagged"),
        CLASS("class"),
        STRUCT("struct"),
        ;
        private final String string;

        Tag(final String string) {
            this.string = string;
        }

        public String toString() {
            return string;
        }
    }

    /**
     * CompoundTypeBuilders are explicitly not thread-safe
     * and should not be shared between threads.
     * 
     * The resulting CompoundType can be shared.
     */
    public static final class Builder {
        final TypeSystem typeSystem;
        Tag tag;
        String name;

        long size;
        int offset;
        int overallAlign;

        ArrayList<CompoundType.Member> members = new ArrayList<>();

        CompoundType completeType;

        Builder(final TypeSystem typeSystem) {
            this.typeSystem = typeSystem;
            this.tag = Tag.NONE;
        }

        public Builder setName(String name) {
            // Don't need to check for null as CompoundType's ctor
            // will assign a name to anything without one.
            this.name = name;
            return this;
        }

        public Builder setTag(Tag tag) {
            this.tag = Objects.requireNonNull(tag);
            return this;
        }

        public Builder setOverallAlignment(int align) {
            if (align < 0) { 
                throw new IllegalStateException("Align must be positive");
            }
            overallAlign = align;
            return this;
        }

        public Builder addNextMember(final ValueType type) {
            Assert.assertTrue(name == null);
            return addNextMember("", type, type.getAlign());
        }

        public Builder addNextMember(final String name, final ValueType type) {
            return addNextMember(name, type, type.getAlign());
        }

        public Builder addNextMember(final String name, final ValueType type, final int align) {
            int thisOffset = nextMemberOffset(offset, align);
            Member m = typeSystem.getCompoundTypeMember(name, type, thisOffset, align);
            // Equivalent to Max(overallAign, Max(type.getAlign(), align))
            overallAlign = Math.max(overallAlign, m.getAlign());
            
            // Update offset to point to the end of the reserved space
            offset = thisOffset + (int)type.getSize();
            members.add(m);
            return this;
        }

        private int nextMemberOffset(int offset, int align) {
            return (offset + (align - 1)) & -align;
        }

        public CompoundType build() {
            if (members.isEmpty()) {
                throw new IllegalStateException("CompoundType has no members");
            }
            if (completeType == null) {
                int size = (offset + (overallAlign - 1)) & -overallAlign;  // Offset points to the end of the structure, align the size to overall alignment
                completeType =  typeSystem.getCompoundType(tag, name, size, overallAlign, () -> members);
            }
            return completeType;
        }
    }
}
