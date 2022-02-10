package org.joinmastodon.android.fragments;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toolbar;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.accounts.GetAccountRelationships;
import org.joinmastodon.android.api.requests.accounts.GetAccountStatuses;
import org.joinmastodon.android.api.requests.accounts.GetOwnAccount;
import org.joinmastodon.android.api.requests.accounts.UpdateAccountCredentials;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.AccountField;
import org.joinmastodon.android.model.Relationship;
import org.joinmastodon.android.ui.drawables.CoverOverlayGradientDrawable;
import org.joinmastodon.android.ui.tabs.TabLayout;
import org.joinmastodon.android.ui.tabs.TabLayoutMediator;
import org.joinmastodon.android.ui.text.HtmlParser;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.views.CoverImageView;
import org.joinmastodon.android.ui.views.NestedRecyclerScrollView;
import org.parceler.Parcels;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewpager2.widget.ViewPager2;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.BaseRecyclerFragment;
import me.grishka.appkit.fragments.LoaderFragment;
import me.grishka.appkit.fragments.OnBackPressedListener;
import me.grishka.appkit.imageloader.ViewImageLoader;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.CubicBezierInterpolator;
import me.grishka.appkit.utils.V;

public class ProfileFragment extends LoaderFragment implements OnBackPressedListener{
	private static final int AVATAR_RESULT=722;
	private static final int COVER_RESULT=343;

	private ImageView avatar;
	private CoverImageView cover;
	private View avatarBorder;
	private TextView name, username, bio, followersCount, followersLabel, followingCount, followingLabel, postsCount, postsLabel;
	private Button actionButton;
	private ViewPager2 pager;
	private NestedRecyclerScrollView scrollView;
	private AccountTimelineFragment postsFragment, postsWithRepliesFragment, mediaFragment;
	private ProfileAboutFragment aboutFragment;
	private TabLayout tabbar;
	private SwipeRefreshLayout refreshLayout;
	private CoverOverlayGradientDrawable coverGradient=new CoverOverlayGradientDrawable();
	private float titleTransY;
	private View postsBtn, followersBtn, followingBtn;
	private EditText nameEdit, bioEdit;

	private Account account;
	private String accountID;
	private Relationship relationship;
	private int statusBarHeight;
	private boolean isOwnProfile;
	private ArrayList<AccountField> fields=new ArrayList<>();

	private boolean isInEditMode;
	private Uri editNewAvatar, editNewCover;

	public ProfileFragment(){
		super(R.layout.loader_fragment_overlay_toolbar);
	}

	@Override
	public void onAttach(Activity activity){
		super.onAttach(activity);
		accountID=getArguments().getString("account");
		setHasOptionsMenu(true);
		if(!getArguments().getBoolean("noAutoLoad", false))
			loadData();
	}

	@Override
	public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState){
		View content=inflater.inflate(R.layout.fragment_profile, container, false);

		avatar=content.findViewById(R.id.avatar);
		cover=content.findViewById(R.id.cover);
		avatarBorder=content.findViewById(R.id.avatar_border);
		name=content.findViewById(R.id.name);
		username=content.findViewById(R.id.username);
		bio=content.findViewById(R.id.bio);
		followersCount=content.findViewById(R.id.followers_count);
		followersLabel=content.findViewById(R.id.followers_label);
		followersBtn=content.findViewById(R.id.followers_btn);
		followingCount=content.findViewById(R.id.following_count);
		followingLabel=content.findViewById(R.id.following_label);
		followingBtn=content.findViewById(R.id.following_btn);
		postsCount=content.findViewById(R.id.posts_count);
		postsLabel=content.findViewById(R.id.posts_label);
		postsBtn=content.findViewById(R.id.posts_btn);
		actionButton=content.findViewById(R.id.profile_action_btn);
		pager=content.findViewById(R.id.pager);
		scrollView=content.findViewById(R.id.scroller);
		tabbar=content.findViewById(R.id.tabbar);
		refreshLayout=content.findViewById(R.id.refresh_layout);
		nameEdit=content.findViewById(R.id.name_edit);
		bioEdit=content.findViewById(R.id.bio_edit);

		avatar.setOutlineProvider(new ViewOutlineProvider(){
			@Override
			public void getOutline(View view, Outline outline){
				outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), V.dp(25));
			}
		});
		avatar.setClipToOutline(true);

		pager.setOffscreenPageLimit(4);
		pager.setAdapter(new ProfilePagerAdapter());
		pager.getLayoutParams().height=getResources().getDisplayMetrics().heightPixels;

		if(getArguments().containsKey("profileAccount")){
			account=Parcels.unwrap(getArguments().getParcelable("profileAccount"));
			isOwnProfile=AccountSessionManager.getInstance().isSelf(accountID, account);
			bindHeaderView();
			dataLoaded();
			if(!isOwnProfile)
				loadRelationship();
		}

		scrollView.setScrollableChildSupplier(this::getScrollableRecyclerView);

		FrameLayout sizeWrapper=new FrameLayout(getActivity()){
			@Override
			protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec){
				Toolbar toolbar=getToolbar();
				pager.getLayoutParams().height=MeasureSpec.getSize(heightMeasureSpec)-getPaddingTop()-getPaddingBottom()-toolbar.getLayoutParams().height-statusBarHeight-V.dp(38);
				coverGradient.setTopPadding(statusBarHeight+toolbar.getLayoutParams().height);
				super.onMeasure(widthMeasureSpec, heightMeasureSpec);
			}
		};
		sizeWrapper.addView(content);

		tabbar.setTabTextColors(getResources().getColor(R.color.gray_500), getResources().getColor(R.color.gray_800));
		tabbar.setTabTextSize(V.dp(16));
		new TabLayoutMediator(tabbar, pager, new TabLayoutMediator.TabConfigurationStrategy(){
			@Override
			public void onConfigureTab(@NonNull TabLayout.Tab tab, int position){
				tab.setText(switch(position){
					case 0 -> R.string.posts;
					case 1 -> R.string.posts_and_replies;
					case 2 -> R.string.media;
					case 3 -> R.string.profile_about;
					default -> throw new IllegalStateException();
				});
			}
		}).attach();

		cover.setForeground(coverGradient);
		cover.setOutlineProvider(new ViewOutlineProvider(){
			@Override
			public void getOutline(View view, Outline outline){
				outline.setEmpty();
			}
		});

		actionButton.setOnClickListener(this::onActionButtonClick);
		avatar.setOnClickListener(this::onAvatarClick);
		cover.setOnClickListener(this::onCoverClick);

		return sizeWrapper;
	}

	@Override
	protected void doLoadData(){
	}

	@Override
	public void onRefresh(){

	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState){
		super.onViewCreated(view, savedInstanceState);
		updateToolbar();
		// To avoid the callback triggering on first layout with position=0 before anything is instantiated
		pager.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){
			@Override
			public boolean onPreDraw(){
				pager.getViewTreeObserver().removeOnPreDrawListener(this);
				pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback(){
					@Override
					public void onPageSelected(int position){
						if(position==0)
							return;
						Fragment _page=getFragmentForPage(position);
						if(_page instanceof BaseRecyclerFragment){
							BaseRecyclerFragment page=(BaseRecyclerFragment) _page;
							if(!page.loaded && !page.isDataLoading())
								page.loadData();
						}
					}
				});
				return true;
			}
		});

		scrollView.setOnScrollChangeListener(this::onScrollChanged);
		titleTransY=getToolbar().getLayoutParams().height;
		if(toolbarTitleView!=null){
			toolbarTitleView.setTranslationY(titleTransY);
			toolbarSubtitleView.setTranslationY(titleTransY);
		}
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig){
		super.onConfigurationChanged(newConfig);
		updateToolbar();
	}

	@Override
	public void onApplyWindowInsets(WindowInsets insets){
		statusBarHeight=insets.getSystemWindowInsetTop();
		((ViewGroup.MarginLayoutParams)getToolbar().getLayoutParams()).topMargin=statusBarHeight;
		refreshLayout.setProgressViewEndTarget(true, statusBarHeight+refreshLayout.getProgressCircleDiameter()+V.dp(24));
		super.onApplyWindowInsets(insets.replaceSystemWindowInsets(insets.getSystemWindowInsetLeft(), 0, insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()));
	}

	private void bindHeaderView(){
		setTitle(account.displayName);
		setSubtitle(getResources().getQuantityString(R.plurals.x_posts, account.statusesCount, account.statusesCount));
		ViewImageLoader.load(avatar, null, new UrlImageLoaderRequest(account.avatar, V.dp(100), V.dp(100)));
		ViewImageLoader.load(cover, null, new UrlImageLoaderRequest(account.header, 1000, 1000));
		name.setText(account.displayName);
		username.setText('@'+account.acct);
		bio.setText(HtmlParser.parse(account.note, account.emojis));
		followersCount.setText(UiUtils.abbreviateNumber(account.followersCount));
		followingCount.setText(UiUtils.abbreviateNumber(account.followingCount));
		postsCount.setText(UiUtils.abbreviateNumber(account.statusesCount));
		followersLabel.setText(getResources().getQuantityString(R.plurals.followers, account.followersCount));
		followingLabel.setText(getResources().getQuantityString(R.plurals.following, account.followingCount));
		postsLabel.setText(getResources().getQuantityString(R.plurals.posts, account.statusesCount));

		if(AccountSessionManager.getInstance().isSelf(accountID, account)){
			actionButton.setText(R.string.edit_profile);
		}else{
			actionButton.setVisibility(View.GONE);
		}

		fields.clear();

		AccountField joined=new AccountField();
		joined.name=getString(R.string.profile_joined);
		joined.parsedValue=joined.value=DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(LocalDateTime.ofInstant(account.createdAt, ZoneId.systemDefault()));
		fields.add(joined);

		for(AccountField field:account.fields){
			field.parsedValue=HtmlParser.parse(field.value, account.emojis);
			fields.add(field);
		}

		if(aboutFragment!=null){
			aboutFragment.setFields(fields);
		}
	}

	private void updateToolbar(){
		getToolbar().setBackgroundColor(0);
		if(toolbarTitleView!=null){
			toolbarTitleView.setTranslationY(titleTransY);
			toolbarSubtitleView.setTranslationY(titleTransY);
		}
	}

	@Override
	public boolean wantsLightStatusBar(){
		return false;
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater){
		if(isOwnProfile && isInEditMode){
			Button cancelButton=new Button(getActivity(), null, 0, R.style.Widget_Mastodon_Button_Secondary);
			cancelButton.setText(R.string.cancel);
			cancelButton.setOnClickListener(v->exitEditMode());
			FrameLayout wrap=new FrameLayout(getActivity());
			wrap.addView(cancelButton, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP|Gravity.LEFT));
			wrap.setPadding(V.dp(16), V.dp(4), V.dp(16), V.dp(8));
			wrap.setClipToPadding(false);
			MenuItem item=menu.add(R.string.cancel);
			item.setActionView(wrap);
			item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
			return;
		}
		if(relationship==null)
			return;
		inflater.inflate(R.menu.profile, menu);
		menu.findItem(R.id.mention).setTitle(getString(R.string.mention_user, account.displayName));
		menu.findItem(R.id.share).setTitle(getString(R.string.share_user, account.displayName));
		menu.findItem(R.id.mute).setTitle(getString(relationship.muting ? R.string.unmute_user : R.string.mute_user, account.displayName));
		menu.findItem(R.id.block).setTitle(getString(relationship.blocking ? R.string.unblock_user : R.string.block_user, account.displayName));
		menu.findItem(R.id.report).setTitle(getString(R.string.report_user, account.displayName));
		String domain=account.getDomain();
		if(domain!=null)
			menu.findItem(R.id.block_domain).setTitle(getString(relationship.domainBlocking ? R.string.unblock_domain : R.string.block_domain, domain));
		else
			menu.findItem(R.id.block_domain).setVisible(false);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item){
		int id=item.getItemId();
		if(id==R.id.share){
			Intent intent=new Intent(Intent.ACTION_SEND);
			intent.setType("text/plain");
			intent.putExtra(Intent.EXTRA_TEXT, account.url);
			startActivity(Intent.createChooser(intent, item.getTitle()));
		}
		return true;
	}

	@Override
	protected int getToolbarResource(){
		return R.layout.profile_toolbar;
	}

	private void loadRelationship(){
		new GetAccountRelationships(Collections.singletonList(account.id))
				.setCallback(new Callback<>(){
					@Override
					public void onSuccess(List<Relationship> result){
						relationship=result.get(0);
						invalidateOptionsMenu();
						actionButton.setVisibility(View.VISIBLE);
						actionButton.setText(relationship.following ? R.string.button_following : R.string.button_follow);
					}

					@Override
					public void onError(ErrorResponse error){

					}
				})
				.exec(accountID);
	}

	private void onScrollChanged(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY){
		int topBarsH=getToolbar().getHeight()+statusBarHeight;
		if(scrollY>avatar.getTop()-topBarsH){
			float avaAlpha=Math.max(1f-((scrollY-(avatar.getTop()-topBarsH))/(float)V.dp(38)), 0f);
			avatar.setAlpha(avaAlpha);
			avatarBorder.setAlpha(avaAlpha);
		}else{
			avatar.setAlpha(1f);
			avatarBorder.setAlpha(1f);
		}
		if(scrollY>cover.getHeight()-topBarsH){
			cover.setTranslationY(scrollY-(cover.getHeight()-topBarsH));
			cover.setTranslationZ(V.dp(10));
			cover.setTransform(cover.getHeight()/2f-topBarsH/2f, 1f);
		}else{
			cover.setTranslationY(0f);
			cover.setTranslationZ(0f);
			cover.setTransform(scrollY/2f, 1f);
		}
		coverGradient.setTopOffset(scrollY);
		cover.invalidate();
		titleTransY=getToolbar().getHeight();
		if(scrollY>name.getTop()-topBarsH){
			titleTransY=Math.max(0f, titleTransY-(scrollY-(name.getTop()-topBarsH)));
		}
		if(toolbarTitleView!=null){
			toolbarTitleView.setTranslationY(titleTransY);
			toolbarSubtitleView.setTranslationY(titleTransY);
		}
	}

	private Fragment getFragmentForPage(int page){
		return switch(page){
			case 0 -> postsFragment;
			case 1 -> postsWithRepliesFragment;
			case 2 -> mediaFragment;
			case 3 -> aboutFragment;
			default -> throw new IllegalStateException();
		};
	}

	private RecyclerView getScrollableRecyclerView(){
		return getFragmentForPage(pager.getCurrentItem()).getView().findViewById(R.id.list);
	}

	private void onActionButtonClick(View v){
		if(isOwnProfile){
			if(!isInEditMode)
				loadAccountInfoAndEnterEditMode();
			else
				saveAndExitEditMode();
		}
	}

	private void loadAccountInfoAndEnterEditMode(){
		new GetOwnAccount()
				.setCallback(new Callback<>(){
					@Override
					public void onSuccess(Account result){
						enterEditMode(result);
					}

					@Override
					public void onError(ErrorResponse error){
						error.showToast(getActivity());
					}
				})
				.wrapProgress(getActivity(), R.string.loading, true)
				.exec(accountID);
	}

	private void enterEditMode(Account account){
		if(isInEditMode)
			throw new IllegalStateException();
		isInEditMode=true;
		invalidateOptionsMenu();
		pager.setUserInputEnabled(false);
		actionButton.setText(R.string.done);
		pager.setCurrentItem(3);
		ArrayList<Animator> animators=new ArrayList<>();
		for(int i=0;i<3;i++){
			animators.add(ObjectAnimator.ofFloat(tabbar.getTabAt(i).view, View.ALPHA, .3f));
			tabbar.getTabAt(i).view.setEnabled(false);
		}
		Drawable overlay=getResources().getDrawable(R.drawable.edit_avatar_overlay).mutate();
		avatar.setForeground(overlay);
		animators.add(ObjectAnimator.ofInt(overlay, "alpha", 0, 255));

		nameEdit.setVisibility(View.VISIBLE);
		nameEdit.setText(account.displayName);
		RelativeLayout.LayoutParams lp=(RelativeLayout.LayoutParams) username.getLayoutParams();
		lp.addRule(RelativeLayout.BELOW, R.id.name_edit);
		username.getParent().requestLayout();
		animators.add(ObjectAnimator.ofFloat(nameEdit, View.ALPHA, 0f, 1f));

		bioEdit.setVisibility(View.VISIBLE);
		bioEdit.setText(account.source.note);
		animators.add(ObjectAnimator.ofFloat(bioEdit, View.ALPHA, 0f, 1f));
		animators.add(ObjectAnimator.ofFloat(bio, View.ALPHA, 0f));

		animators.add(ObjectAnimator.ofFloat(postsBtn, View.ALPHA, .3f));
		animators.add(ObjectAnimator.ofFloat(followersBtn, View.ALPHA, .3f));
		animators.add(ObjectAnimator.ofFloat(followingBtn, View.ALPHA, .3f));

		AnimatorSet set=new AnimatorSet();
		set.playTogether(animators);
		set.setDuration(300);
		set.setInterpolator(CubicBezierInterpolator.DEFAULT);
		set.start();

		aboutFragment.enterEditMode(account.source.fields);
	}

	private void exitEditMode(){
		if(!isInEditMode)
			throw new IllegalStateException();
		isInEditMode=false;

		invalidateOptionsMenu();
		ArrayList<Animator> animators=new ArrayList<>();
		actionButton.setText(R.string.edit_profile);
		for(int i=0;i<3;i++){
			animators.add(ObjectAnimator.ofFloat(tabbar.getTabAt(i).view, View.ALPHA, 1f));
		}
		animators.add(ObjectAnimator.ofInt(avatar.getForeground(), "alpha", 0));
		animators.add(ObjectAnimator.ofFloat(nameEdit, View.ALPHA, 0f));
		animators.add(ObjectAnimator.ofFloat(bioEdit, View.ALPHA, 0f));
		animators.add(ObjectAnimator.ofFloat(bio, View.ALPHA, 1f));
		animators.add(ObjectAnimator.ofFloat(postsBtn, View.ALPHA, 1f));
		animators.add(ObjectAnimator.ofFloat(followersBtn, View.ALPHA, 1f));
		animators.add(ObjectAnimator.ofFloat(followingBtn, View.ALPHA, 1f));

		AnimatorSet set=new AnimatorSet();
		set.playTogether(animators);
		set.setDuration(200);
		set.setInterpolator(CubicBezierInterpolator.DEFAULT);
		set.addListener(new AnimatorListenerAdapter(){
			@Override
			public void onAnimationEnd(Animator animation){
				for(int i=0;i<3;i++){
					tabbar.getTabAt(i).view.setEnabled(true);
				}
				pager.setUserInputEnabled(true);
				nameEdit.setVisibility(View.GONE);
				bioEdit.setVisibility(View.GONE);
				RelativeLayout.LayoutParams lp=(RelativeLayout.LayoutParams) username.getLayoutParams();
				lp.addRule(RelativeLayout.BELOW, R.id.name);
				username.getParent().requestLayout();
				avatar.setForeground(null);
			}
		});
		set.start();

		bindHeaderView();
	}

	private void saveAndExitEditMode(){
		if(!isInEditMode)
			throw new IllegalStateException();
		new UpdateAccountCredentials(nameEdit.getText().toString(), bioEdit.getText().toString(), editNewAvatar, editNewCover, aboutFragment.getFields())
				.setCallback(new Callback<>(){
					@Override
					public void onSuccess(Account result){
						account=result;
						AccountSessionManager.getInstance().updateAccountInfo(accountID, account);
						exitEditMode();
					}

					@Override
					public void onError(ErrorResponse error){
						error.showToast(getActivity());
					}
				})
				.wrapProgress(getActivity(), R.string.saving, false)
				.exec(accountID);
	}

	@Override
	public boolean onBackPressed(){
		if(isInEditMode){
			exitEditMode();
			return true;
		}
		return false;
	}

	private void onAvatarClick(View v){
		if(isInEditMode){
			startImagePicker(AVATAR_RESULT);
		}
	}

	private void onCoverClick(View v){
		if(isInEditMode){
			startImagePicker(COVER_RESULT);
		}
	}

	private void startImagePicker(int requestCode){
		Intent intent=new Intent(Intent.ACTION_GET_CONTENT);
		intent.setType("image/*");
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		startActivityForResult(intent, requestCode);
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data){
		if(resultCode==Activity.RESULT_OK){
			if(requestCode==AVATAR_RESULT){
				editNewAvatar=data.getData();
				ViewImageLoader.load(avatar, null, new UrlImageLoaderRequest(editNewAvatar, V.dp(100), V.dp(100)));
			}else if(requestCode==COVER_RESULT){
				editNewCover=data.getData();
				ViewImageLoader.load(cover, null, new UrlImageLoaderRequest(editNewCover, V.dp(1000), V.dp(1000)));
			}
		}
	}

	private class ProfilePagerAdapter extends RecyclerView.Adapter<SimpleViewHolder>{
		@NonNull
		@Override
		public SimpleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType){
			FrameLayout view=new FrameLayout(getActivity());
			view.setId(View.generateViewId());
			view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
			return new SimpleViewHolder(view);
		}

		@Override
		public void onBindViewHolder(@NonNull SimpleViewHolder holder, int position){
			Fragment fragment=switch(position){
				case 0 -> postsFragment=AccountTimelineFragment.newInstance(accountID, account, GetAccountStatuses.Filter.DEFAULT, true);
				case 1 -> postsWithRepliesFragment=AccountTimelineFragment.newInstance(accountID, account, GetAccountStatuses.Filter.INCLUDE_REPLIES, false);
				case 2 -> mediaFragment=AccountTimelineFragment.newInstance(accountID, account, GetAccountStatuses.Filter.MEDIA, false);
				case 3 -> {
					aboutFragment=new ProfileAboutFragment();
					aboutFragment.setFields(fields);
					yield aboutFragment;
				}
				default -> throw new IllegalArgumentException();
			};
			getChildFragmentManager().beginTransaction().add(holder.itemView.getId(), fragment).commit();
		}

		@Override
		public int getItemCount(){
			return 4;
		}

		@Override
		public int getItemViewType(int position){
			return position;
		}
	}

	private class SimpleViewHolder extends RecyclerView.ViewHolder{
		public SimpleViewHolder(@NonNull View itemView){
			super(itemView);
		}
	}
}
