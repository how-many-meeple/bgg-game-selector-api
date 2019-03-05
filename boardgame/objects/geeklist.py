from boardgamegeek import BGGError
from boardgamegeek.objects.things import Thing
from boardgamegeek.utils import DictObject


class GeekListComment(DictObject):
    """
    Object containing details about a comment in a geeklist
    """
    def __init__(self, data):
        super(GeekListComment, self).__init__(data)

    def __repr__(self):
        return "GeekListComment (on {} by [{}])".format(self.date, self.username)

    def _format(self, log):
        log.info("date           : {}".format(self.date))
        log.info("  username     : {}".format(self.username))
        log.info("  postdate     : {}".format(self.postdate))
        log.info("  editdate     : {}".format(self.editdate))
        log.info("  thumbs count : {}".format(self.thumbs))
        log.info("  text         : {}".format(self.text))


class GeekList(Thing):
    """
    Object containing information about a geeklist
    """
    def __init__(self, data):
        self._comments = []
        self._items = []
        super(GeekList, self).__init__(data)

    def __repr__(self):
        return "GeekList (id: {})".format(self.id)

    def __len__(self):
        return len(self._items)

    def add_comment(self, comment_data):
        """
        Add a comment to the ``GeekList``

        :param dict comment_data: comment data
        :raises: :py:class:`boardgamegeek.exceptions.BoardGameGeekError` in case of invalid data
        """
        try:
            comment = GeekListComment(comment_data)
        except KeyError:
            raise BGGError("invalid item data")
        self._comments.append(comment)
        return comment

    def add_item(self, item_data):
        """
        Add an item to the ``GeekList``

        :param dict item_data: item data
        :raises: :py:class:`boardgamegeek.exceptions.BoardGameGeekError` in case of invalid data
        """
        try:
            item = GeekListItem(item_data)
        except KeyError:
            raise BGGError("invalid item data")
        self._items.append(item)
        return item

    @property
    def comments(self):
        """
        Returns the comments in the collection

        :returns: the comments in the geeklist
        :rtype: list of :py:class:`boardgamegeek.games.CollectionBoardGame`
        """
        return self._comments

    @property
    def items(self):
        """
        Returns the items in the geeklist

        :returns: the items in the geeklist
        :rtype: list of :py:class:`boardgamegeek.games.CollectionBoardGame`
        """
        return self._items

    def __iter__(self):
        for item in self._items:
            yield item

    def _format(self, log):
        log.info("geeklist id           : {}".format(self.id))
        log.info("geeklist name (title) : {}".format(self.name))
        log.info("geeklist posted at    : {}".format(self.postdate))
        log.info("geeklist edited at    : {}".format(self.editdate))
        log.info("geeklist thumbs count : {}".format(self.thumbs))
        log.info("geeklist numitems     : {}".format(self.numitems))
        log.info("geeklist description  : {}".format(self.description))
        log.info("comments")
        for c in self.comments:
            c._format(log)
            log.info("")
        log.info("items")
        for i in self:
            i._format(log)
            log.info("")

    @property
    def title(self):
        # alias for name
        return self.name


class GeekListItem(DictObject):
    """
    Object containing information about a geeklist item
    """
    def __init__(self, data):
        self._comments = []
        super(GeekListItem, self).__init__(data)

    def __repr__(self):
        return "GeekListItem (id: {})".format(self.id)

    def set_object(self, object_data):
        """
        Set the object in the ``GeekListItem``

        :param dict object_data: objects data
        :raises: :py:class:`boardgamegeek.exceptions.BoardGameGeekError` in case of invalid data
        """
        try:
            self._object = GeekListObject(object_data)
        except KeyError:
            raise BGGError("invalid object data")
        return self._object

    def add_comment(self, comment_data):
        """
        Add a comment to the ``GeekList``

        :param dict comment_data: comment data
        :raises: :py:class:`boardgamegeek.exceptions.BoardGameGeekError` in case of invalid data
        """
        try:
            comment = GeekListComment(comment_data)
        except KeyError:
            raise BGGError("invalid item data")
        self._comments.append(comment)
        return comment

    @property
    def comments(self):
        """
        Returns the comments in the collection

        :returns: the comments in the geeklist
        :rtype: list of :py:class:`boardgamegeek.games.CollectionBoardGame`
        """
        return self._comments

    def _format(self, log):
        log.info("id                 : {}".format(self.id))
        log.info("username           : {}".format(self.username))
        log.info("object")
        self.object._format(log)
        log.info("posted at          : {}".format(self.postdate))
        log.info("edited at          : {}".format(self.editdate))
        log.info("thumbs count       : {}".format(self.thumbs))
        log.info("body (description) : {}".format(self.body))
        log.info("comments")
        for c in self.comments:
            c._format(log)
            log.info("")

    @property
    def object(self):
        return self._object

    @property
    def description(self):
        # alias for body
        return self.body


class GeekListObject(Thing):
    """
    Object containing information about a geeklist object (e.g. a game reference)
    """
    def __init__(self, data):
        self._items = []
        super(GeekListObject, self).__init__(data)

    def __repr__(self):
        return "GeekListItem (id: {})".format(self.id)

    def _format(self, log):
        log.info("id      : {}".format(self.id))
        log.info(u"name    : {}".format(self.name))  # Name may contain unicode chars, was an issue with python2. TODO:  Shouldn't we fix that everywhere?
        log.info("imageid : {}".format(self.imageid))
        log.info("type    : {}".format(self.type))
        log.info("subtype : {}".format(self.subtype))
