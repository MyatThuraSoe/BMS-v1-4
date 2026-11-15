package com.bms.service;

import com.bms.entity.TransferSequence;
import com.bms.repository.TransferSequenceRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SequenceServiceTransferNumberTest {

    @Test
    void transferNumbersIncrementOnTheLockedShopDateSequence() {
        TransferSequenceRepository repository = mock(TransferSequenceRepository.class);
        ShopTimeService shopTimeService = new ShopTimeService(null, "UTC");
        TransferSequence sequence = new TransferSequence();
        sequence.setSequenceDate(shopTimeService.today());
        sequence.setLastNumber(0);
        when(repository.findByDateForUpdate(sequence.getSequenceDate())).thenReturn(Optional.of(sequence));
        SequenceService service = new SequenceService(null, null, null, repository, shopTimeService);

        String first = service.nextTransferNumber();
        String second = service.nextTransferNumber();

        assertNotEquals(first, second);
        assertTrue(first.endsWith("0001"));
        assertTrue(second.endsWith("0002"));
    }
}
