package ru.practicum.shareit.item.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.shareit.booking.repository.BookingRepository;
import ru.practicum.shareit.booking.mapper.BookingMapper;
import ru.practicum.shareit.booking.model.Booking;
import ru.practicum.shareit.booking.status.BookingStatus;
import ru.practicum.shareit.exception.EntityNotFoundException;
import ru.practicum.shareit.exception.NotBookerException;
import ru.practicum.shareit.exception.NotOwnerException;
import ru.practicum.shareit.comment.model.Comment;
import ru.practicum.shareit.comment.repository.CommentRepository;
import ru.practicum.shareit.comment.dto.CommentDtoIn;
import ru.practicum.shareit.comment.dto.CommentDtoOut;
import ru.practicum.shareit.comment.mapper.CommentMapper;
import ru.practicum.shareit.item.ItemDtoIn;
import ru.practicum.shareit.item.ItemDtoOut;
import ru.practicum.shareit.item.mapper.ItemMapper;
import ru.practicum.shareit.item.model.Item;
import ru.practicum.shareit.item.repository.ItemRepository;
import ru.practicum.shareit.request.model.ItemRequest;
import ru.practicum.shareit.request.repository.ItemRequestRepository;
import ru.practicum.shareit.user.User;
import ru.practicum.shareit.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static org.springframework.data.domain.Sort.Direction.ASC;
import static org.springframework.data.domain.Sort.Direction.DESC;

@Slf4j
@Transactional
@Service
@RequiredArgsConstructor
public class ItemService {
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;
    private final CommentRepository commentRepository;
    private final ItemRequestRepository requestRepository;

    public ItemDtoOut saveNewItem(ItemDtoIn itemDtoIn, int userId) {
        log.info("Создание новой вещи {}", itemDtoIn.getName());
        User owner = getUser(userId);

        Item item = ItemMapper.toItem(itemDtoIn);
        item.setOwner(owner);
        Integer requestId = itemDtoIn.getRequestId();
        if (requestId != null) {
            item.setRequest(requestRepository.findById(requestId).orElseThrow(() ->
                    new EntityNotFoundException(String.format("Объект класса %s не найден", ItemRequest.class))));
        }
        return ItemMapper.toItemDtoOut(itemRepository.save(item));
    }

    public ItemDtoOut updateItem(int itemId, ItemDtoIn itemDtoIn, int userId) {
        log.info("Обновление вещи {} с идентификатором {}", itemDtoIn.getName(), itemId);
        getUser(userId);
        Item item = getItem(itemId);
        String name = itemDtoIn.getName();
        String description = itemDtoIn.getDescription();
        Boolean available = itemDtoIn.getAvailable();
        if (item.getOwner().getId() == userId) {
            if (name != null && !name.isBlank()) {
                item.setName(name);
            }
            if (description != null && !description.isBlank()) {
                item.setDescription(description);
            }
            if (available != null) {
                item.setAvailable(available);
            }
        } else {
            throw new NotOwnerException(String.format("Пользователь с id %s не является собственником %s",
                    userId, name));
        }
        return ItemMapper.toItemDtoOut(item);
    }

    @Transactional(readOnly = true)
    public ItemDtoOut getItemById(int itemId, int userId) {
        log.info("Получение вещи по идентификатору {}", itemId);
        final Item item = getItem(itemId);
        return addBookingsAndComments(item, userId);
    }

    @Transactional(readOnly = true)
    public List<ItemDtoOut> getItemsByOwner(Integer from, Integer size, int userId) {
        log.info("Получение вещи по владельцу {}", userId);
        getUser(userId);
        List<Item> items = itemRepository.findAllByOwnerId(userId, PageRequest.of(from / size, size));
        return addBookingsAndCommentsForList(items);
    }

    @Transactional(readOnly = true)
    public List<ItemDtoOut> getItemBySearch(Integer from, Integer size, String text) {
        log.info("Получение вещи по поиску {}", text);
        if (text.isBlank()) {
            return Collections.emptyList();
        }
        return itemRepository.search(text, PageRequest.of(from / size, size)).stream()
                .map(ItemMapper::toItemDtoOut).collect(toList());
    }

    public CommentDtoOut saveNewComment(int itemId, CommentDtoIn commentDtoIn, int userId) {
        if (!bookingRepository.existsByBookerIdAndItemIdAndEndBefore(userId, itemId, LocalDateTime.now())) {
            throw new NotBookerException("Пользователь не пользовался вещью");
        }
        User user = getUser(userId);
        Item item = getItem(itemId);
        Comment comment = commentRepository.save(CommentMapper.toComment(commentDtoIn, item, user));
        return CommentMapper.toCommentDtoOut(comment);
    }

    private ItemDtoOut addBookingsAndComments(Item item, int userId) {
        ItemDtoOut itemDtoOut = ItemMapper.toItemDtoOut(item);

        LocalDateTime thisMoment = LocalDateTime.now();
        if (itemDtoOut.getOwner().getId() == userId) {
            itemDtoOut.setLastBooking(bookingRepository
                    .findFirstByItemIdAndStartLessThanEqualAndStatus(itemDtoOut.getId(), thisMoment,
                            BookingStatus.APPROVED, Sort.by(DESC, "end"))
                    .map(BookingMapper::toBookingDtoShort)
                    .orElse(null));

            itemDtoOut.setNextBooking(bookingRepository
                    .findFirstByItemIdAndStartAfterAndStatus(itemDtoOut.getId(), thisMoment,
                            BookingStatus.APPROVED, Sort.by(ASC, "end"))
                    .map(BookingMapper::toBookingDtoShort)
                    .orElse(null));
        }

        itemDtoOut.setComments(commentRepository.findAllByItemId(itemDtoOut.getId())
                .stream()
                .map(CommentMapper::toCommentDtoOut)
                .collect(toList()));

        return itemDtoOut;
    }

    private List<ItemDtoOut> addBookingsAndCommentsForList(List<Item> items) {
        LocalDateTime thisMoment = LocalDateTime.now();

        Map<Item, Booking> itemsWithLastBookings = bookingRepository
                .findByItemInAndStartLessThanEqualAndStatus(items, thisMoment,
                        BookingStatus.APPROVED, Sort.by(DESC, "end"))
                .stream()
                .collect(Collectors.toMap(Booking::getItem, Function.identity(), (o1, o2) -> o1));

        Map<Item, Booking> itemsWithNextBookings = bookingRepository
                .findByItemInAndStartAfterAndStatus(items, thisMoment,
                        BookingStatus.APPROVED, Sort.by(ASC, "end"))
                .stream()
                .collect(Collectors.toMap(Booking::getItem, Function.identity(), (o1, o2) -> o1));

        Map<Item, List<Comment>> itemsWithComments = commentRepository
                .findByItemIn(items, Sort.by(DESC, "created"))
                .stream()
                .collect(groupingBy(Comment::getItem, toList()));

        List<ItemDtoOut> itemDtoOuts = new ArrayList<>();
        for (Item item : items) {
            ItemDtoOut itemDtoOut = ItemMapper.toItemDtoOut(item);
            Booking lastBooking = itemsWithLastBookings.get(item);
            if (itemsWithLastBookings.size() > 0 && lastBooking != null) {
                itemDtoOut.setLastBooking(BookingMapper.toBookingDtoShort(lastBooking));
            }
            Booking nextBooking = itemsWithNextBookings.get(item);
            if (itemsWithNextBookings.size() > 0 && nextBooking != null) {
                itemDtoOut.setNextBooking(BookingMapper.toBookingDtoShort(nextBooking));
            }
            List<CommentDtoOut> commentDtoOuts = itemsWithComments.getOrDefault(item, Collections.emptyList())
                    .stream()
                    .map(CommentMapper::toCommentDtoOut)
                    .collect(toList());
            itemDtoOut.setComments(commentDtoOuts);

            itemDtoOuts.add(itemDtoOut);
        }
        return itemDtoOuts;
    }

    private User getUser(int userId) {
        return userRepository.findById(userId).orElseThrow(() ->
                new EntityNotFoundException(String.format("Объект класса %s не найден", User.class)));
    }

    private Item getItem(int itemId) {
        return itemRepository.findById(itemId).orElseThrow(() ->
                new EntityNotFoundException(String.format("Объект класса %s не найден", Item.class)));
    }
}