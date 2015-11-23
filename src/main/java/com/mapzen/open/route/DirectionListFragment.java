package com.mapzen.open.route;

import com.mapzen.open.R;
import com.mapzen.pelias.SimpleFeature;
import com.mapzen.open.util.DisplayHelper;
import com.mapzen.open.widget.DistanceView;
import com.mapzen.osrm.Instruction;

import android.content.Context;
import android.os.Bundle;
import android.support.v4.app.ListFragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;

import butterknife.ButterKnife;
import butterknife.InjectView;

import static com.mapzen.pelias.SimpleFeature.TEXT;

public class DirectionListFragment extends ListFragment {
    public static final String TAG = DirectionListFragment.class.getSimpleName();
    private List<Instruction> instructions;
    private DirectionListener listener;
    private SimpleFeature destination;
    private boolean reverse;

    @InjectView(R.id.starting_point) TextView startingPointTextView;
    @InjectView(R.id.destination) TextView destinationTextView;
    @InjectView(R.id.starting_location_icon) ImageView startLocationIcon;
    @InjectView(R.id.destination_location_icon) ImageView destinationLocationIcon;
    @InjectView(R.id.route_reverse) ImageButton routeReverse;
    @InjectView(android.R.id.list) ListView listView;

    public static DirectionListFragment newInstance(List<Instruction> instructions,
            DirectionListener listener,  SimpleFeature destination, boolean reverse) {
        final DirectionListFragment fragment = new DirectionListFragment();
        fragment.instructions = instructions;
        fragment.listener = listener;
        fragment.destination = destination;
        fragment.reverse = reverse;
        fragment.setRetainInstance(true);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_direction_list, container, false);
        ButterKnife.inject(this, view);
        routeReverse.setVisibility(View.GONE);
        listView.setAdapter(new DirectionListAdapter(getActivity(), instructions, reverse));
        setOriginAndDestination();
        return view;
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        if (listener != null) {
            listener.onInstructionSelected(position - 1);
        }

        getActivity().onBackPressed();
    }

    public interface DirectionListener {
        public void onInstructionSelected(int index);
    }

    private static class DirectionListAdapter extends BaseAdapter {
        private static final int CURRENT_LOCATION_OFFSET = 1;
        private Context context;
        private List<Instruction> instructions;
        private boolean reversed;

        public DirectionListAdapter(Context context, List<Instruction> instructions,
                                    boolean reversed) {
            this.context = context;
            this.instructions = instructions;
            this.reversed = reversed;
        }

        @Override
        public int getCount() {
            return instructions.size() + CURRENT_LOCATION_OFFSET;
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View view = View.inflate(context, R.layout.direction_list_item, null);
            if (reversed) {
                setReversedDirectionListItem(position, view);
            } else {
                setDirectionListItem(position, view);
            }
            return view;
        }

        private void setDirectionListItem(int position, View view) {
            if (position == 0) {
                setListItemToCurrentLocation(view);
            } else {
                setListItemToInstruction(view, position - CURRENT_LOCATION_OFFSET);
            }
        }

        private void setReversedDirectionListItem(int position, View view) {
            if (position == instructions.size()) {
                setListItemToCurrentLocation(view);
            } else {
                setListItemToInstruction(view, position);
            }
        }

        public void setListItemToCurrentLocation(View view) {
            ImageView icon = (ImageView) view.findViewById(R.id.icon);
            TextView simpleInstruction = (TextView)
                    view.findViewById(R.id.simple_instruction);

            icon.setImageResource(R.drawable.ic_locate_active);
            simpleInstruction.setText(context.getResources()
                    .getString(R.string.current_location));
        }

        public void setListItemToInstruction(View view, int position) {
            ImageView icon = (ImageView) view.findViewById(R.id.icon);
            TextView simpleInstruction = (TextView)
                    view.findViewById(R.id.simple_instruction);
            DistanceView distance = (DistanceView) view.findViewById(R.id.distance);
            Instruction current = instructions.get(position);

            icon.setImageResource(DisplayHelper.getRouteDrawable(context,
                    current.getTurnInstruction(), DisplayHelper.IconStyle.GRAY));
            simpleInstruction.setText(current.getSimpleInstruction(context));
            distance.setDistance(current.getDistance());
        }
    }

    private void setOriginAndDestination() {
        if (reverse) {
            startingPointTextView.setText(destination.getProperty(TEXT));
            destinationTextView.setText(getString(R.string.current_location));
            startLocationIcon.setVisibility(View.GONE);
            destinationLocationIcon.setVisibility(View.VISIBLE);
        } else {
            startingPointTextView.setText(getString(R.string.current_location));
            destinationTextView.setText(destination.getProperty(TEXT));
            startLocationIcon.setVisibility(View.VISIBLE);
            destinationLocationIcon.setVisibility(View.GONE);
        }
    }
}
