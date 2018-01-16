//Copyright (c) 2017. 章钦豪. All rights reserved.
package com.monke.monkeybook.presenter;

import android.support.annotation.NonNull;

import com.hwangjr.rxbus.RxBus;
import com.hwangjr.rxbus.annotation.Subscribe;
import com.hwangjr.rxbus.annotation.Tag;
import com.hwangjr.rxbus.thread.EventThread;
import com.monke.basemvplib.IView;
import com.monke.basemvplib.impl.BasePresenterImpl;
import com.monke.monkeybook.base.observer.SimpleObserver;
import com.monke.monkeybook.bean.BookShelfBean;
import com.monke.monkeybook.bean.BookSourceBean;
import com.monke.monkeybook.bean.SearchBookBean;
import com.monke.monkeybook.bean.SearchHistoryBean;
import com.monke.monkeybook.common.RxBusTag;
import com.monke.monkeybook.dao.DbHelper;
import com.monke.monkeybook.dao.SearchHistoryBeanDao;
import com.monke.monkeybook.listener.OnGetChapterListListener;
import com.monke.monkeybook.model.BookSourceManage;
import com.monke.monkeybook.model.SearchBook;
import com.monke.monkeybook.model.WebBookModelImpl;
import com.monke.monkeybook.presenter.impl.ISearchPresenter;
import com.monke.monkeybook.utils.NetworkUtil;
import com.monke.monkeybook.view.impl.ISearchView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class SearchPresenterImpl extends BasePresenterImpl<ISearchView> implements ISearchPresenter {
    private static final String TAG_KEY = "tag";
    private static final String HAS_MORE_KEY = "hasMore";
    private static final String HAS_LOAD_KEY = "hasLoad";
    private static final String DUR_REQUEST_TIME = "durRequestTime";    //当前搜索引擎失败次数  成功一次会重新开始计数
    private static final String MAX_REQUEST_TIME = "maxRequestTime";   //最大连续请求失败次数

    private static final int BOOK = 2;

    private Boolean hasSearch = false;   //判断是否搜索过

    private int page = 1;
    private List<Map> searchEngine;
    private long startThisSearchTime;
    private String durSearchKey;

    private List<BookShelfBean> bookShelfS = new ArrayList<>();   //用来比对搜索的书籍是否已经添加进书架

    private Boolean isInput = false;
    private SearchBook searchBook;

    public SearchPresenterImpl() {
        Observable.create((ObservableOnSubscribe<List<BookShelfBean>>) e -> {
            List<BookShelfBean> temp = DbHelper.getInstance().getmDaoSession().getBookShelfBeanDao().queryBuilder().list();
            if (temp == null)
                temp = new ArrayList<>();
            e.onNext(temp);
            e.onComplete();
        }).subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<List<BookShelfBean>>() {
                    @Override
                    public void onNext(List<BookShelfBean> value) {
                        bookShelfS.addAll(value);
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                    }
                });

        //搜索引擎初始化
        searchBook = new SearchBook(new SearchBook.OnSearchListener() {
            @Override
            public void refreshSearchBook(List<SearchBookBean> value) {
                mView.refreshSearchBook(value);
            }

            @Override
            public void refreshFinish(Boolean value) {
                mView.refreshFinish(value);
            }

            @Override
            public void loadMoreFinish(Boolean value) {
                mView.loadMoreFinish(value);
            }

            @Override
            public Boolean checkIsExist(SearchBookBean value) {
                return mView.checkIsExist(value);
            }

            @Override
            public void loadMoreSearchBook(List<SearchBookBean> value) {
                mView.loadMoreSearchBook(value);
            }

            @Override
            public void searchBookError(Boolean value) {
                mView.searchBookError(value);
            }

            @Override
            public int getItemCount() {
                return mView.getSearchBookAdapter().getItemcount();
            }
        });
//        searchEngine = new ArrayList<>();
//        for (BookSourceBean bookSourceBean: BookSourceManage.getSelectedBookSource()) {
//            Map se = new HashMap();
//            se.put(TAG_KEY, bookSourceBean.getBookSourceUrl());
//            se.put(HAS_MORE_KEY, true);
//            se.put(HAS_LOAD_KEY, false);
//            se.put(DUR_REQUEST_TIME, 1);
//            se.put(MAX_REQUEST_TIME, 3);
//            searchEngine.add(se);
//        }
    }

    @Override
    public Boolean getHasSearch() {
        return hasSearch;
    }

    @Override
    public void setHasSearch(Boolean hasSearch) {
        this.hasSearch = hasSearch;
    }

    @Override
    public void insertSearchHistory() {
        final int type = SearchPresenterImpl.BOOK;
        final String content = mView.getEdtContent().getText().toString().trim();
        Observable.create((ObservableOnSubscribe<SearchHistoryBean>) e -> {
            List<SearchHistoryBean> data = DbHelper.getInstance().getmDaoSession().getSearchHistoryBeanDao()
                    .queryBuilder()
                    .where(SearchHistoryBeanDao.Properties.Type.eq(type), SearchHistoryBeanDao.Properties.Content.eq(content))
                    .limit(1)
                    .build().list();
            SearchHistoryBean searchHistoryBean = null;
            if (null != data && data.size() > 0) {
                searchHistoryBean = data.get(0);
                searchHistoryBean.setDate(System.currentTimeMillis());
                DbHelper.getInstance().getmDaoSession().getSearchHistoryBeanDao().update(searchHistoryBean);
            } else {
                searchHistoryBean = new SearchHistoryBean(type, content, System.currentTimeMillis());
                DbHelper.getInstance().getmDaoSession().getSearchHistoryBeanDao().insert(searchHistoryBean);
            }
            e.onNext(searchHistoryBean);
        }).subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<SearchHistoryBean>() {
                    @Override
                    public void onNext(SearchHistoryBean value) {
                        mView.insertSearchHistorySuccess(value);
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                    }
                });
    }

    @Override
    public void cleanSearchHistory() {
        final String content = mView.getEdtContent().getText().toString().trim();
        Observable.create((ObservableOnSubscribe<Integer>) e -> {
            int a = DbHelper.getInstance().getDb().delete(SearchHistoryBeanDao.TABLENAME,
                    SearchHistoryBeanDao.Properties.Type.columnName + "=? and " + SearchHistoryBeanDao.Properties.Content.columnName + " like ?",
                    new String[]{String.valueOf(SearchPresenterImpl.BOOK), "%" + content + "%"});
            e.onNext(a);
        }).subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<Integer>() {
                    @Override
                    public void onNext(Integer value) {
                        if (value > 0) {
                            mView.querySearchHistorySuccess(null);
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                    }
                });
    }

    @Override
    public void querySearchHistory() {
        final String content = mView.getEdtContent().getText().toString().trim();
        Observable.create((ObservableOnSubscribe<List<SearchHistoryBean>>) e -> {
            List<SearchHistoryBean> data = DbHelper.getInstance().getmDaoSession().getSearchHistoryBeanDao()
                    .queryBuilder()
                    .where(SearchHistoryBeanDao.Properties.Type.eq(SearchPresenterImpl.BOOK), SearchHistoryBeanDao.Properties.Content.like("%" + content + "%"))
                    .orderDesc(SearchHistoryBeanDao.Properties.Date)
                    .limit(20)
                    .build().list();
            e.onNext(data);
        }).subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<List<SearchHistoryBean>>() {
                    @Override
                    public void onNext(List<SearchHistoryBean> value) {
                        if (null != value)
                            mView.querySearchHistorySuccess(value);
                    }

                    @Override
                    public void onError(Throwable e) {

                    }
                });
    }

    @Override
    public int getPage() {
        return page;
    }

    @Override
    public void initPage() {
        this.page = 1;
    }

    @Override
    public void toSearchBooks(String key, Boolean fromError) {
        if (key != null) {
            durSearchKey = key;
            searchBook.setSearchTime(System.currentTimeMillis());
            for (int i = 0; i < searchEngine.size(); i++) {
                searchEngine.get(i).put(HAS_MORE_KEY, true);
                searchEngine.get(i).put(HAS_LOAD_KEY, false);
                searchEngine.get(i).put(DUR_REQUEST_TIME, 1);
            }
        }
        searchBook.search(durSearchKey, startThisSearchTime, fromError);
    }
//    //搜索书集
//    private void searchBook(final String content, final long searchTime, Boolean fromError) {
//        if (searchTime == startThisSearchTime) {
//            Boolean canLoad = false;
//            for (Map temp : searchEngine) {
//                if ((Boolean) temp.get(HAS_MORE_KEY) && (int) temp.get(DUR_REQUEST_TIME) <= (int) temp.get(MAX_REQUEST_TIME)) {
//                    canLoad = true;
//                    break;
//                }
//            }
//            if (canLoad) {
//                int searchEngineIndex = -1;
//                for (int i = 0; i < searchEngine.size(); i++) {
//                    if (!(Boolean) searchEngine.get(i).get(HAS_LOAD_KEY) && (int) searchEngine.get(i).get(DUR_REQUEST_TIME) <= (int) searchEngine.get(i).get(MAX_REQUEST_TIME)) {
//                        searchEngineIndex = i;
//                        break;
//                    }
//                }
//                if (searchEngineIndex == -1) {
//                    this.page++;
//                    for (Map item : searchEngine) {
//                        item.put(HAS_LOAD_KEY, false);
//                    }
//                    if (!fromError) {
//                        if (page - 1 == 1) {
//                            mView.refreshFinish(false);
//                        } else {
//                            mView.loadMoreFinish(false);
//                        }
//                    } else {
//                        searchBook(content, searchTime, false);
//                    }
//                } else {
//                    final int finalSearchEngineIndex = searchEngineIndex;
//                    WebBookModelImpl.getInstance().searchOtherBook(content, page, (String) searchEngine.get(searchEngineIndex).get(TAG_KEY))
//                            .observeOn(AndroidSchedulers.mainThread())
//                            .subscribeOn(Schedulers.newThread())
//                            .subscribe(new SimpleObserver<List<SearchBookBean>>() {
//                                @Override
//                                public void onNext(List<SearchBookBean> value) {
//                                    if (searchTime == startThisSearchTime) {
//                                        searchEngine.get(finalSearchEngineIndex).put(HAS_LOAD_KEY, true);
//                                        searchEngine.get(finalSearchEngineIndex).put(DUR_REQUEST_TIME, 1);
//                                        if (value.size() == 0) {
//                                            searchEngine.get(finalSearchEngineIndex).put(HAS_MORE_KEY, false);
//                                        } else {
//                                            for (SearchBookBean temp : value) {
//                                                for (BookShelfBean bookShelfBean : bookShelfS) {
//                                                    if (temp.getNoteUrl().equals(bookShelfBean.getNoteUrl())) {
//                                                        temp.setAdd(true);
//                                                        break;
//                                                    }
//                                                }
//                                            }
//                                        }
//                                        if (page == 1 && finalSearchEngineIndex == 0) {
//                                            mView.refreshSearchBook(value);
//                                        } else {
//                                            if (value.size() > 0 && !mView.checkIsExist(value.get(0)))
//                                                mView.loadMoreSearchBook(value);
//                                            else {
//                                                searchEngine.get(finalSearchEngineIndex).put(HAS_MORE_KEY, false);
//                                            }
//                                        }
//                                        searchBook(content, searchTime, false);
//                                    }
//                                }
//
//                                @Override
//                                public void onError(Throwable e) {
//                                    e.printStackTrace();
//                                    if (searchTime == startThisSearchTime) {
//                                        searchEngine.get(finalSearchEngineIndex).put(HAS_LOAD_KEY, false);
//                                        searchEngine.get(finalSearchEngineIndex).put(DUR_REQUEST_TIME, ((int) searchEngine.get(finalSearchEngineIndex).get(DUR_REQUEST_TIME)) + 1);
//                                        mView.searchBookError(page == 1 && (finalSearchEngineIndex == 0 || (finalSearchEngineIndex > 0 && mView.getSearchBookAdapter().getItemcount() == 0)));
//                                    }
//                                }
//                            });
//                }
//            } else {
//                if (page == 1) {
//                    mView.refreshFinish(true);
//                } else {
//                    mView.loadMoreFinish(true);
//                }
//                this.page++;
//                for (Map item : searchEngine) {
//                    item.put(HAS_LOAD_KEY, false);
//                }
//            }
//        }
//    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //添加书集
    @Override
    public void addBookToShelf(final SearchBookBean searchBookBean) {
        final BookShelfBean bookShelfResult = new BookShelfBean();
        bookShelfResult.setNoteUrl(searchBookBean.getNoteUrl());
        bookShelfResult.setFinalDate(0);
        bookShelfResult.setDurChapter(0);
        bookShelfResult.setDurChapterPage(0);
        bookShelfResult.setTag(searchBookBean.getTag());
        WebBookModelImpl.getInstance().getBookInfo(bookShelfResult)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<BookShelfBean>() {
                    @Override
                    public void onNext(BookShelfBean value) {
                        WebBookModelImpl.getInstance().getChapterList(value, new OnGetChapterListListener() {
                            @Override
                            public void success(BookShelfBean bookShelfBean) {
                                saveBookToShelf(bookShelfBean);
                            }

                            @Override
                            public void error() {
                                mView.addBookShelfFailed(NetworkUtil.ERROR_CODE_OUTTIME);
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable e) {
                        mView.addBookShelfFailed(NetworkUtil.ERROR_CODE_OUTTIME);
                    }
                });
    }

    private void saveBookToShelf(final BookShelfBean bookShelfBean) {
        Observable.create((ObservableOnSubscribe<BookShelfBean>) e -> {
            DbHelper.getInstance().getmDaoSession().getChapterListBeanDao().insertOrReplaceInTx(bookShelfBean.getBookInfoBean().getChapterlist());
            DbHelper.getInstance().getmDaoSession().getBookInfoBeanDao().insertOrReplace(bookShelfBean.getBookInfoBean());
            //网络数据获取成功  存入BookShelf表数据库
            DbHelper.getInstance().getmDaoSession().getBookShelfBeanDao().insertOrReplace(bookShelfBean);
            e.onNext(bookShelfBean);
            e.onComplete();
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<BookShelfBean>() {
                    @Override
                    public void onNext(BookShelfBean value) {
                        //成功   //发送RxBus
                        RxBus.get().post(RxBusTag.HAD_ADD_BOOK, value);
                    }

                    @Override
                    public void onError(Throwable e) {
                        mView.addBookShelfFailed(NetworkUtil.ERROR_CODE_OUTTIME);
                    }
                });
    }
    ////////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void attachView(@NonNull IView iView) {
        super.attachView(iView);
        RxBus.get().register(this);
    }

    @Override
    public void detachView() {
        RxBus.get().unregister(this);
    }

    @Subscribe(
            thread = EventThread.MAIN_THREAD,
            tags = {@Tag(RxBusTag.HAD_ADD_BOOK)})
    public void hadAddBook(BookShelfBean bookShelfBean) {
        bookShelfS.add(bookShelfBean);
        List<SearchBookBean> data = mView.getSearchBookAdapter().getSearchBooks();
        for (int i = 0; i < data.size(); i++) {
            if (data.get(i).getNoteUrl().equals(bookShelfBean.getNoteUrl())) {
                data.get(i).setAdd(true);
                mView.updateSearchItem(i);
                break;
            }
        }
    }

    @Subscribe(thread = EventThread.MAIN_THREAD,
            tags = {@Tag(RxBusTag.HAD_REMOVE_BOOK)})
    public void hadRemoveBook(BookShelfBean bookShelfBean) {
        if (bookShelfS != null) {
            for (int i = 0; i < bookShelfS.size(); i++) {
                if (bookShelfS.get(i).getNoteUrl().equals(bookShelfBean.getNoteUrl())) {
                    bookShelfS.remove(i);
                    break;
                }
            }
        }
        List<SearchBookBean> data = mView.getSearchBookAdapter().getSearchBooks();
        for (int i = 0; i < data.size(); i++) {
            if (data.get(i).getNoteUrl().equals(bookShelfBean.getNoteUrl())) {
                data.get(i).setAdd(false);
                mView.updateSearchItem(i);
                break;
            }
        }
    }

    @Override
    public Boolean getInput() {
        return isInput;
    }

    @Override
    public void setInput(Boolean input) {
        isInput = input;
    }
}