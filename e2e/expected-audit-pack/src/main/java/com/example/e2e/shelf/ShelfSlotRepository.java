package com.example.e2e.shelf;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ShelfSlotRepository extends JpaRepository<ShelfSlot, String> {
    /** A bulk update: the auditing listener does not run, lastModifiedBy stays as it was. */
    @Modifying
    @Query("update ShelfSlot s set s.status = :status where s.id = :id")
    int markStatus(String id, String status);

    /** Sets the editor itself: audited. */
    @Modifying
    @Query("update ShelfSlot s set s.status = :status, s.lastModifiedBy = :by where s.id = :id")
    int markStatusBy(String id, String status, String by);

    /** A delete: no row left to say who changed it last. */
    @Modifying
    @Query("delete from ShelfSlot s where s.status = 'EMPTY'")
    int purgeEmpty();
}
