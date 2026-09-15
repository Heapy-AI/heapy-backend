package com.heapy.shop;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 미션 코인·상점·옷장 응답 계약. @author 김진우 */
public final class ShopModels {
    private ShopModels() { }
    public record Wallet(long balance, int missionReward) { }
    public record Item(String itemId, String name, String slot, int price, String assetKey,
                       boolean owned, boolean equipped) { }
    public record Wardrobe(List<Item> items, String equippedItemId) { }
    public record PurchaseRequest(@NotBlank String itemId) { }
    public record EquipRequest(@NotBlank String itemId) { }
    public record Purchase(UUID purchaseId, String itemId, Instant purchasedAt, Instant cancelUntil,
                           Instant cancelledAt, int spentCoins) { }
    public record PurchaseResult(Purchase purchase, long balance) { }
}
