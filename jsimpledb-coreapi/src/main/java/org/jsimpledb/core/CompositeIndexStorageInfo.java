
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package org.jsimpledb.core;

import java.util.List;
import java.util.stream.Collectors;

class CompositeIndexStorageInfo extends StorageInfo {

    final List<SimpleFieldStorageInfo<?>> fields;

    CompositeIndexStorageInfo(CompositeIndex index) {
        super(index.storageId);
        this.fields = index.fields.stream()
          .map(SimpleField::toStorageInfo)
          .collect(Collectors.toList());
    }

    Object getIndex(Transaction tx) {
        switch (fields.size()) {
        case 2:
            return this.buildIndex(tx, this.fields.get(0).fieldType, this.fields.get(1).fieldType);
        case 3:
            return this.buildIndex(tx, this.fields.get(0).fieldType, this.fields.get(1).fieldType, this.fields.get(2).fieldType);
        case 4:
            return this.buildIndex(tx, this.fields.get(0).fieldType, this.fields.get(1).fieldType,
              this.fields.get(2).fieldType, this.fields.get(3).fieldType);
        // COMPOSITE-INDEX
        default:
            throw new RuntimeException("internal error");
        }
    }

    // This method exists solely to bind the generic type parameters
    private <V1, V2> CoreIndex2<V1, V2, ObjId> buildIndex(Transaction tx,
      FieldType<V1> value1Type,
      FieldType<V2> value2Type) {
        return new CoreIndex2<>(tx, new Index2View<>(this.storageId,
          value1Type,
          value2Type,
          FieldTypeRegistry.OBJ_ID));
    }

    // This method exists solely to bind the generic type parameters
    private <V1, V2, V3> CoreIndex3<V1, V2, V3, ObjId> buildIndex(Transaction tx,
      FieldType<V1> value1Type,
      FieldType<V2> value2Type,
      FieldType<V3> value3Type) {
        return new CoreIndex3<>(tx, new Index3View<>(this.storageId,
          value1Type,
          value2Type,
          value3Type,
          FieldTypeRegistry.OBJ_ID));
    }

    // This method exists solely to bind the generic type parameters
    private <V1, V2, V3, V4> CoreIndex4<V1, V2, V3, V4, ObjId> buildIndex(Transaction tx,
      FieldType<V1> value1Type,
      FieldType<V2> value2Type,
      FieldType<V3> value3Type,
      FieldType<V4> value4Type) {
        return new CoreIndex4<>(tx, new Index4View<>(this.storageId,
          value1Type,
          value2Type,
          value3Type,
          value4Type,
          FieldTypeRegistry.OBJ_ID));
    }

// Object

    @Override
    public String toString() {
        return "composite index on fields " + this.fields;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this)
            return true;
        if (!super.equals(obj))
            return false;
        final CompositeIndexStorageInfo that = (CompositeIndexStorageInfo)obj;
        return this.fields.equals(that.fields);
    }

    @Override
    public int hashCode() {
        return super.hashCode() ^ this.fields.hashCode();
    }
}

